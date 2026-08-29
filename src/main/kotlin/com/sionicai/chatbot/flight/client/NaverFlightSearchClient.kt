package com.sionicai.chatbot.flight.client

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * 네이버 항공권의 내부 검색 API를 호출하는 클라이언트. `flight.provider=naver` 일 때 활성화.
 *
 * ## 비공식(undocumented) API 입니다
 * 네이버는 항공권 검색에 대한 공개 API를 제공하지 않으며, 이 클라이언트는 웹 프론트엔드가 사용하는
 * 내부 엔드포인트를 직접 호출합니다. 다음 한계를 전제로 사용하세요.
 *
 * - **요청 스키마가 문서화되어 있지 않습니다.** 그래서 요청 본문을 코드에 박지 않고
 *   JSON 템플릿 리소스(`flight/naver-search-request-*.json`)로 분리했습니다.
 *   브라우저 DevTools > Network 에서 `searchFlights` 요청 본문을 캡처해 템플릿에 붙여넣고
 *   가변값만 플레이스홀더로 바꾸면, 재컴파일 없이 스키마 변경에 대응할 수 있습니다.
 * - **응답 스키마도 고정할 수 없습니다.** 파싱은 [FlightOfferExtractor]가 JSON 트리를 훑는 방식이라
 *   필드 위치가 바뀌어도 이름만 유지되면 동작합니다. 후보 키는 `flight.extractor.*` 설정으로 넓힐 수 있습니다.
 * - **네이버는 클라우드(AWS 등) IP 대역을 차단합니다.** 로컬에서 되더라도 서버 배포 시 실패할 수 있습니다.
 *   이 저장소는 IP 우회나 차단 회피 로직을 포함하지 않습니다.
 *
 * 연동이 깨졌을 때는 `POST /api/flights/diagnostics` 로 실제 요청/응답을 확인하세요.
 */
@Component
@ConditionalOnProperty(name = ["flight.provider"], havingValue = "naver")
class NaverFlightSearchClient(
    private val objectMapper: ObjectMapper,
    private val extractor: FlightOfferExtractor,
    @Value("\${flight.naver.base-url}") private val baseUrl: String,
    @Value("\${flight.naver.search-path}") private val searchPath: String,
    @Value("\${flight.naver.referer}") private val referer: String,
    @Value("\${flight.naver.user-agent}") private val userAgent: String,
    @Value("\${flight.naver.timeout-seconds}") private val timeoutSeconds: Long,
    @Value("\${flight.naver.request-template-oneway}") private val onewayTemplate: Resource,
    @Value("\${flight.naver.request-template-roundtrip}") private val roundTripTemplate: Resource
) : FlightSearchClient {

    private val logger = LoggerFactory.getLogger(NaverFlightSearchClient::class.java)

    override val providerName = "naver"

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("User-Agent", userAgent)
            .defaultHeader("Referer", referer)
            .defaultHeader("Origin", referer.trimEnd('/'))
            .defaultHeader("Accept", "text/event-stream, application/json")
            .defaultHeader("Accept-Language", "ko-KR,ko;q=0.9")
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            // 여러 항공사 결과가 누적되어 수백 KB까지 커질 수 있습니다.
            .codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES) }
            .build()
    }

    private val onewayTemplateText: String by lazy { onewayTemplate.readText() }
    private val roundTripTemplateText: String by lazy { roundTripTemplate.readText() }

    override fun search(query: FlightSearchQuery): List<FlightOffer> {
        val attempt = execute(query)

        attempt.error?.let { throw FlightSearchException(it) }

        if (attempt.offers.isEmpty()) {
            // 스키마가 바뀌었을 때 원인을 좁힐 수 있도록 응답 앞부분을 남깁니다.
            logger.warn(
                "[NaverFlight] 응답에서 가격을 찾지 못했습니다. 응답 일부: {}",
                attempt.rawResponseHead
            )
            throw FlightSearchException(
                "네이버 항공권 응답에서 가격을 찾지 못했습니다. " +
                    "응답 스키마가 바뀌었을 수 있습니다. POST /api/flights/diagnostics 로 확인하세요."
            )
        }

        return attempt.offers
    }

    override fun diagnose(query: FlightSearchQuery) = execute(query).let {
        SearchDiagnostics(
            provider = providerName,
            requestBody = it.requestBody,
            rawResponseHead = it.rawResponseHead,
            responseBytes = it.responseBytes,
            matchedPriceKeys = it.matchedPriceKeys,
            offers = it.offers,
            error = it.error
        )
    }

    /** 검색을 한 번 수행하고, 성공/실패와 무관하게 관찰된 사실을 그대로 담아 돌려줍니다. */
    private fun execute(query: FlightSearchQuery): Attempt {
        val requestBody = renderRequestBody(query)

        logger.info(
            "[NaverFlight] {} -> {} ({}{}) 조회",
            query.origin,
            query.destination,
            query.departureDate,
            query.returnDate?.let { " ~ $it" } ?: ""
        )

        val raw = try {
            webClient.post()
                .uri(searchPath)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String::class.java)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block()
        } catch (e: Exception) {
            return Attempt(
                requestBody = requestBody,
                error = "네이버 항공권 호출 실패 (${query.origin}->${query.destination}): ${e.message}"
            )
        }

        if (raw.isNullOrBlank()) {
            return Attempt(requestBody = requestBody, error = "네이버 항공권 응답이 비어 있습니다")
        }

        val extraction = parse(raw)
        return Attempt(
            requestBody = requestBody,
            rawResponseHead = raw.take(RESPONSE_HEAD_CHARS),
            responseBytes = raw.length,
            offers = extraction.offers,
            matchedPriceKeys = extraction.matchedPriceKeys
        )
    }

    /**
     * 요청 본문을 템플릿에서 만들어 냅니다.
     * 날짜는 어떤 포맷을 요구하는지 확정할 수 없어 두 형태를 모두 제공합니다.
     */
    internal fun renderRequestBody(query: FlightSearchQuery): String {
        val template = if (query.isRoundTrip) roundTripTemplateText else onewayTemplateText

        val values = mapOf(
            "origin" to query.origin,
            "destination" to query.destination,
            "departureDate" to query.departureDate.format(COMPACT_DATE),
            "departureDateDash" to query.departureDate.toString(),
            "returnDate" to (query.returnDate?.format(COMPACT_DATE) ?: ""),
            "returnDateDash" to (query.returnDate?.toString() ?: ""),
            "adults" to query.adults.toString(),
            "cabin" to query.cabin.naverCode,
            "tripType" to if (query.isRoundTrip) "RT" else "OW"
        )

        val rendered = values.entries.fold(template) { acc, (key, value) ->
            acc.replace("{{$key}}", value)
        }

        // 치환되지 않은 플레이스홀더가 남아 있으면 그대로 보내지 말고 즉시 알립니다.
        UNRESOLVED_PLACEHOLDER.find(rendered)?.let {
            throw FlightSearchException("요청 템플릿에 알 수 없는 플레이스홀더가 있습니다: ${it.value}")
        }

        val node = try {
            objectMapper.readTree(rendered)
        } catch (e: Exception) {
            throw FlightSearchException("요청 템플릿이 유효한 JSON 이 아닙니다: ${e.message}", e)
        }

        // 템플릿의 사용법 설명(_comment 등)까지 네이버로 보낼 이유는 없으므로 걷어냅니다.
        if (node.isObject) {
            val objectNode = node as com.fasterxml.jackson.databind.node.ObjectNode
            objectNode.fieldNames().asSequence().filter { it.startsWith("_") }.toList()
                .forEach { objectNode.remove(it) }
        }
        return objectMapper.writeValueAsString(node)
    }

    /**
     * 응답은 SSE(`data: {...}`)로 여러 청크에 나뉘어 옵니다.
     * 순수 JSON으로 오는 경우도 있어 두 형태를 모두 처리합니다.
     */
    private fun parse(raw: String): ExtractionResult {
        val chunks = raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .toList()
            .ifEmpty { listOf(raw.trim()) }

        val offers = mutableListOf<FlightOffer>()
        val matchedKeys = linkedSetOf<String>()

        chunks.forEach { chunk ->
            val node = try {
                objectMapper.readTree(chunk)
            } catch (e: Exception) {
                logger.debug("[NaverFlight] JSON 으로 파싱되지 않는 청크 무시: {}", e.message)
                return@forEach
            }
            val result = extractor.extract(node)
            offers += result.offers
            matchedKeys += result.matchedPriceKeys
        }

        return ExtractionResult(
            offers = offers.distinctBy { it.price to it.airline }.sortedBy { it.price },
            matchedPriceKeys = matchedKeys.toList()
        )
    }

    private fun Resource.readText(): String =
        inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    private data class Attempt(
        val requestBody: String,
        val rawResponseHead: String? = null,
        val responseBytes: Int? = null,
        val offers: List<FlightOffer> = emptyList(),
        val matchedPriceKeys: List<String> = emptyList(),
        val error: String? = null
    )

    companion object {
        private val COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val UNRESOLVED_PLACEHOLDER = Regex("\\{\\{[^}]*}}")
        private const val RESPONSE_HEAD_CHARS = 2_000
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    }
}

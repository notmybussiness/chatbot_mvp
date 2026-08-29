package com.sionicai.chatbot.flight.client

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import java.time.LocalDate

/**
 * 요청 본문 템플릿 렌더링 테스트.
 *
 * 네이버 요청 스키마는 확정할 수 없으므로 본문을 템플릿 리소스로 분리했습니다.
 * 여기서는 "템플릿이 무엇이든 플레이스홀더가 올바르게 채워지고 유효한 JSON 이 나온다"는
 * 성질을 검증합니다. 필드명 자체는 사용자가 DevTools 캡처로 교체하는 값입니다.
 */
class NaverRequestTemplateTest {

    private val objectMapper = ObjectMapper()

    private fun clientWith(oneway: String? = null, roundTrip: String? = null) =
        NaverFlightSearchClient(
            objectMapper = objectMapper,
            extractor = FlightOfferExtractor(
                priceKeys = "totalFare",
                airlineKeys = "airlineName",
                stopKeys = "stopCount",
                currencyKeys = "currency",
                linkKeys = "deepLink",
                minPrice = 10_000
            ),
            baseUrl = "https://flight-api.naver.com",
            searchPath = "/flight/international/searchFlights",
            referer = "https://flight.naver.com/",
            userAgent = "test-agent",
            timeoutSeconds = 5,
            onewayTemplate = oneway?.let { ByteArrayResource(it.toByteArray()) }
                ?: ClassPathResource("flight/naver-search-request-oneway.json"),
            roundTripTemplate = roundTrip?.let { ByteArrayResource(it.toByteArray()) }
                ?: ClassPathResource("flight/naver-search-request-roundtrip.json")
        )

    private val oneWayQuery = FlightSearchQuery(
        origin = "ICN",
        destination = "NRT",
        departureDate = LocalDate.of(2026, 12, 20)
    )

    @Test
    @DisplayName("기본 편도 템플릿은 유효한 JSON 을 만들어 낸다")
    fun `default one-way template renders valid json`() {
        val body = clientWith().renderRequestBody(oneWayQuery)
        val node = objectMapper.readTree(body)

        assertEquals("OW", node.path("tripType").asText())
        assertEquals(1, node.path("adult").asInt())
        assertEquals("Y", node.path("fareType").asText())

        val legs = node.path("fly")
        assertEquals(1, legs.size())
        assertEquals("ICN", legs[0].path("departureAirport").asText())
        assertEquals("NRT", legs[0].path("arrivalAirport").asText())
        assertEquals("20261220", legs[0].path("departureDate").asText())
    }

    @Test
    @DisplayName("기본 왕복 템플릿은 복편 구간을 포함한다")
    fun `default round-trip template includes the return leg`() {
        val query = oneWayQuery.copy(returnDate = LocalDate.of(2026, 12, 27), adults = 2)
        val node = objectMapper.readTree(clientWith().renderRequestBody(query))

        assertEquals("RT", node.path("tripType").asText())
        assertEquals(2, node.path("adult").asInt())

        val legs = node.path("fly")
        assertEquals(2, legs.size())
        assertEquals("NRT", legs[1].path("departureAirport").asText())
        assertEquals("ICN", legs[1].path("arrivalAirport").asText())
        assertEquals("20261227", legs[1].path("departureDate").asText())
    }

    @Test
    @DisplayName("좌석 등급이 네이버 코드로 치환된다")
    fun `cabin class maps to naver code`() {
        val body = clientWith().renderRequestBody(oneWayQuery.copy(cabin = CabinClass.BUSINESS))

        assertEquals("C", objectMapper.readTree(body).path("fareType").asText())
    }

    @Test
    @DisplayName("대시 포함 날짜 형식도 제공한다")
    fun `dashed date placeholder is supported`() {
        val body = clientWith(oneway = """{ "date": "{{departureDateDash}}" }""")
            .renderRequestBody(oneWayQuery)

        assertEquals("2026-12-20", objectMapper.readTree(body).path("date").asText())
    }

    @Test
    @DisplayName("사용자가 템플릿을 통째로 교체해도 그대로 동작한다")
    fun `user supplied template is rendered as-is`() {
        // DevTools 캡처 결과가 전혀 다른 모양이어도 코드 수정 없이 반영되어야 합니다.
        val custom = """
            { "searchKey": "{{origin}}_{{destination}}_{{departureDate}}",
              "pax": { "adt": {{adults}} },
              "cabinCode": "{{cabin}}" }
        """.trimIndent()

        val node = objectMapper.readTree(clientWith(oneway = custom).renderRequestBody(oneWayQuery))

        assertEquals("ICN_NRT_20261220", node.path("searchKey").asText())
        assertEquals(1, node.path("pax").path("adt").asInt())
        assertEquals("Y", node.path("cabinCode").asText())
    }

    @Test
    @DisplayName("알 수 없는 플레이스홀더가 남으면 요청을 보내지 않고 실패시킨다")
    fun `unresolved placeholder fails fast`() {
        val broken = """{ "who": "{{unknownField}}" }"""

        val ex = assertThrows(FlightSearchException::class.java) {
            clientWith(oneway = broken).renderRequestBody(oneWayQuery)
        }
        assertTrue(ex.message!!.contains("{{unknownField}}"))
    }

    @Test
    @DisplayName("템플릿의 설명용 _ 키는 실제 요청에서 제거된다")
    fun `underscore prefixed keys are stripped before sending`() {
        val body = clientWith().renderRequestBody(oneWayQuery)

        assertFalse(body.contains("_comment"))
        assertTrue(objectMapper.readTree(body).path("fly").isArray)
    }

    @Test
    @DisplayName("템플릿이 유효한 JSON 이 아니면 요청을 보내지 않고 실패시킨다")
    fun `invalid json template fails fast`() {
        val ex = assertThrows(FlightSearchException::class.java) {
            clientWith(oneway = """{ "broken": """).renderRequestBody(oneWayQuery)
        }
        assertTrue(ex.message!!.contains("유효한 JSON"))
    }
}

package com.sionicai.chatbot.flight.service

import com.sionicai.chatbot.flight.client.FlightSearchClient
import com.sionicai.chatbot.flight.client.FlightSearchException
import com.sionicai.chatbot.flight.client.FlightSearchQuery
import com.sionicai.chatbot.flight.dto.CollectionResult
import com.sionicai.chatbot.flight.entity.FlightWatch
import com.sionicai.chatbot.flight.entity.PriceSnapshot
import com.sionicai.chatbot.flight.repository.FlightWatchRepository
import com.sionicai.chatbot.flight.repository.PriceSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 활성 노선을 순회하며 그날의 최저가 스냅샷을 저장합니다.
 *
 * 리포트 작성과 분리해 둔 이유는, 수집은 외부 의존적이고 느리지만 리포트는 이미 쌓인 데이터만으로
 * 언제든 다시 만들 수 있기 때문입니다. 덕분에 리포트 조회는 외부 호출을 전혀 일으키지 않습니다.
 */
@Service
class PriceCollectionService(
    private val flightWatchRepository: FlightWatchRepository,
    private val priceSnapshotRepository: PriceSnapshotRepository,
    private val flightSearchClient: FlightSearchClient,
    @Value("\${flight.collection.request-delay-ms}") private val requestDelayMs: Long
) {

    private val logger = LoggerFactory.getLogger(PriceCollectionService::class.java)

    /**
     * 트랜잭션을 열지 않습니다. 노선 사이 지연을 포함하면 수십 초가 걸릴 수 있어 하나의 트랜잭션으로
     * 묶으면 커넥션을 오래 점유하고, 한 노선의 실패가 전체를 롤백시키기 때문입니다.
     * 저장은 노선 단위로 각각 커밋되므로 일부가 실패해도 나머지 수집 결과는 남습니다.
     */
    fun collectAll(collectedOn: LocalDate = LocalDate.now()): CollectionResult {
        val watches = flightWatchRepository.findAllByActiveTrue()
        // 이미 수집된 노선을 노선마다 질의하지 않고 한 번에 확인합니다.
        val alreadyCollected = priceSnapshotRepository.findWatchIdsCollectedOn(collectedOn).toSet()

        var succeeded = 0
        var failed = 0
        var skipped = 0
        var requested = 0

        watches.forEach { watch ->
            val watchId = watch.id!!

            if (watchId in alreadyCollected) {
                logger.debug("[FlightCollect] {} 는 {} 에 이미 수집됨, 건너뜁니다", watch.route, collectedOn)
                skipped++
                return@forEach
            }

            // 외부 소스에 연속 요청을 던지지 않도록 노선 사이에 간격을 둡니다.
            if (requested > 0 && requestDelayMs > 0) {
                java.lang.Thread.sleep(requestDelayMs)
            }
            requested++

            try {
                val offers = flightSearchClient.search(watch.toQuery())
                val best = offers.minByOrNull { it.price }
                    ?: throw FlightSearchException("검색 결과가 비어 있습니다")

                priceSnapshotRepository.save(
                    PriceSnapshot(
                        watchId = watchId,
                        collectedOn = collectedOn,
                        lowestPrice = best.price,
                        currency = best.currency,
                        airline = best.airline,
                        stops = best.stops,
                        offerCount = offers.size,
                        deepLink = best.deepLink,
                        source = flightSearchClient.providerName
                    )
                )
                succeeded++
            } catch (e: Exception) {
                // 한 노선의 실패가 나머지 수집을 막지 않도록 여기서 흡수합니다.
                logger.warn("[FlightCollect] {} 수집 실패: {}", watch.route, e.message)
                failed++
            }
        }

        val result = CollectionResult(collectedOn, succeeded, failed, skipped)
        logger.info("[FlightCollect] 수집 완료: {}", result)
        return result
    }

    private fun FlightWatch.toQuery() = FlightSearchQuery(
        origin = origin,
        destination = destination,
        departureDate = departureDate,
        returnDate = returnDate,
        adults = adults,
        cabin = cabin
    )
}

package com.sionicai.chatbot.flight.client

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

/**
 * Mock 항공권 클라이언트 - 외부 호출 없이 수집·리포트 전체 플로우를 시연할 수 있습니다.
 * `flight.provider=mock` (기본값) 일 때 활성화.
 *
 * 가격은 (검색조건 + 수집일) 시드로 만들어져 같은 날 같은 조건은 항상 같은 값이 나오고,
 * 날짜가 바뀌면 값이 달라집니다. 덕분에 "전일 대비 등락" 리포트를 실제로 확인할 수 있습니다.
 */
@Component
@ConditionalOnProperty(name = ["flight.provider"], havingValue = "mock", matchIfMissing = true)
class MockFlightSearchClient : FlightSearchClient {

    private val logger = LoggerFactory.getLogger(MockFlightSearchClient::class.java)

    override val providerName = "mock"

    override fun search(query: FlightSearchQuery): List<FlightOffer> {
        logger.info(
            "[MockFlight] {} -> {} ({}{}) 조회",
            query.origin,
            query.destination,
            query.departureDate,
            query.returnDate?.let { " ~ $it" } ?: ""
        )

        val random = Random(seedOf(query, LocalDate.now()))
        val basePrice = 180_000L + random.nextInt(320_000)
        val multiplier = query.cabin.priceMultiplier * if (query.isRoundTrip) 1.8 else 1.0

        return AIRLINES.shuffled(random).take(4).mapIndexed { index, airline ->
            // 뒤로 갈수록 비싸지되 편차를 줘서 정렬 결과가 뻔하지 않게 만듭니다.
            val premium = index * (12_000L + random.nextInt(18_000))
            FlightOffer(
                price = ((basePrice + premium) * multiplier).toLong() * query.adults,
                currency = "KRW",
                airline = airline,
                stops = if (random.nextInt(10) < 6) 0 else 1,
                deepLink = "https://flight.naver.com/flights/international/" +
                    "${query.origin}-${query.destination}-${query.departureDate}"
            )
        }.sortedBy { it.price }
    }

    private fun seedOf(query: FlightSearchQuery, collectedOn: LocalDate): Long {
        val routeSeed = abs(
            "${query.origin}${query.destination}${query.departureDate}${query.returnDate}${query.cabin}".hashCode()
        ).toLong()
        return routeSeed * 31 + collectedOn.toEpochDay()
    }

    private val CabinClass.priceMultiplier: Double
        get() = when (this) {
            CabinClass.ECONOMY -> 1.0
            CabinClass.BUSINESS -> 2.7
            CabinClass.FIRST -> 4.2
        }

    companion object {
        private val AIRLINES = listOf(
            "대한항공", "아시아나항공", "제주항공", "티웨이항공", "진에어", "에어부산"
        )
    }
}

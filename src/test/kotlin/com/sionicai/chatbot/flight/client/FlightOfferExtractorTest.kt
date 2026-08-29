package com.sionicai.chatbot.flight.client

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 응답 파싱 단위 테스트.
 *
 * 네이버 응답 스키마를 확정할 수 없기 때문에, 추출기가 "정해진 경로"가 아니라 "필드 이름"에 기대어
 * 동작하는지를 검증합니다. 실제 응답이 어떤 모양으로 오든 이 성질이 유지되어야 연동이 버팁니다.
 */
class FlightOfferExtractorTest {

    private val objectMapper = ObjectMapper()

    private val extractor = FlightOfferExtractor(
        priceKeys = "totalFare,totalPrice,adultFare,lowestFare,fare,price,amount",
        airlineKeys = "airlineName,airline,carrierName,marketingAirline,airlineCode,carrier",
        stopKeys = "stopCount,stops,viaCount,transitCount,stopOverCount",
        currencyKeys = "currency,currencyCode",
        linkKeys = "deepLink,bookingUrl,link,url",
        minPrice = 10_000
    )

    private fun extract(json: String) = extractor.extract(objectMapper.readTree(json))

    @Test
    @DisplayName("평평한 객체에서 가격을 뽑는다")
    fun `extracts from flat object`() {
        val result = extract(
            """
            { "airlineName": "대한항공", "totalFare": 452300, "stopCount": 0, "currency": "KRW" }
            """
        )

        val offer = result.offers.single()
        assertEquals(452_300, offer.price)
        assertEquals("대한항공", offer.airline)
        assertEquals(0, offer.stops)
        assertEquals("KRW", offer.currency)
        assertEquals(listOf("totalFare"), result.matchedPriceKeys)
    }

    @Test
    @DisplayName("가격이 깊이 중첩되어도 상위 노드의 항공사를 물려받는다")
    fun `inherits airline from ancestor nodes`() {
        // 운임 객체가 여정 객체 아래에 중첩되는, 실제 항공 API에서 흔한 형태
        val result = extract(
            """
            {
              "result": {
                "fareResult": {
                  "itineraries": [
                    {
                      "airlineName": "아시아나항공",
                      "stopCount": 1,
                      "fares": { "A01": { "totalFare": 388000, "currency": "KRW" } }
                    }
                  ]
                }
              }
            }
            """
        )

        val offer = result.offers.single()
        assertEquals(388_000, offer.price)
        assertEquals("아시아나항공", offer.airline)
        assertEquals(1, offer.stops)
    }

    @Test
    @DisplayName("가장 가까운 상위 노드의 항공사가 우선한다")
    fun `nearest ancestor airline wins`() {
        val result = extract(
            """
            {
              "airlineName": "대한항공",
              "group": { "airlineName": "제주항공", "totalFare": 210000 }
            }
            """
        )

        assertEquals("제주항공", result.offers.single().airline)
    }

    @Test
    @DisplayName("문자열로 포맷된 가격도 숫자로 읽는다")
    fun `parses formatted string prices`() {
        val result = extract("""{ "airline": "티웨이항공", "price": "1,234,500" }""")

        assertEquals(1_234_500, result.offers.single().price)
    }

    @Test
    @DisplayName("최소 금액 미만은 세금·유류할증료로 보고 무시한다")
    fun `ignores values below the minimum price`() {
        val result = extract("""{ "airline": "진에어", "fare": 8500 }""")

        assertTrue(result.offers.isEmpty())
        assertTrue(result.matchedPriceKeys.isEmpty())
    }

    @Test
    @DisplayName("여러 결과를 모두 모아 가격 오름차순으로 돌려준다")
    fun `collects multiple offers sorted by price`() {
        val result = extract(
            """
            { "flights": [
              { "airlineName": "대한항공",   "totalFare": 520000 },
              { "airlineName": "제주항공",   "totalFare": 310000 },
              { "airlineName": "티웨이항공", "totalFare": 415000 }
            ] }
            """
        )

        assertEquals(listOf(310_000L, 415_000L, 520_000L), result.offers.map { it.price })
        assertEquals("제주항공", result.offers.first().airline)
    }

    @Test
    @DisplayName("같은 운임이 여러 노드에 중복되어도 한 번만 센다")
    fun `deduplicates repeated offers`() {
        val result = extract(
            """
            {
              "summary": { "airlineName": "대한항공", "totalFare": 452300 },
              "detail":  { "airlineName": "대한항공", "totalFare": 452300 }
            }
            """
        )

        assertEquals(1, result.offers.size)
    }

    @Test
    @DisplayName("가격 후보 키는 설정된 순서대로 우선한다")
    fun `price keys are prioritised in configured order`() {
        // totalFare 가 price 보다 앞에 설정되어 있으므로 총액이 선택되어야 합니다.
        val result = extract("""{ "airline": "대한항공", "price": 300000, "totalFare": 452300 }""")

        assertEquals(452_300, result.offers.single().price)
        assertEquals(listOf("totalFare"), result.matchedPriceKeys)
    }

    @Test
    @DisplayName("항공사를 못 찾으면 미상으로 채우고 가격은 살린다")
    fun `keeps offer when airline is unknown`() {
        val result = extract("""{ "totalFare": 452300 }""")

        assertEquals(FlightOfferExtractor.UNKNOWN_AIRLINE, result.offers.single().airline)
    }

    @Test
    @DisplayName("예매 링크도 상위 노드에서 물려받는다")
    fun `inherits deep link from ancestor`() {
        val result = extract(
            """
            {
              "deepLink": "https://flight.naver.com/x",
              "fare": { "totalFare": 452300 }
            }
            """
        )

        assertEquals("https://flight.naver.com/x", result.offers.single().deepLink)
    }

    @Test
    @DisplayName("가격이 없으면 빈 결과를 돌려준다 - 스키마 변경 감지 신호")
    fun `returns empty result when no price is found`() {
        val result = extract("""{ "status": "OK", "flights": [], "message": "no result" }""")

        assertTrue(result.offers.isEmpty())
        // matchedPriceKeys 가 비어 있다는 것은 후보 키를 넓혀야 한다는 신호입니다.
        assertTrue(result.matchedPriceKeys.isEmpty())
    }

    @Test
    @DisplayName("설정으로 후보 키를 넓히면 새로운 필드명도 인식한다")
    fun `widening candidate keys picks up renamed fields`() {
        val widened = FlightOfferExtractor(
            priceKeys = "totalFare,brandNewFareField",
            airlineKeys = "airlineName",
            stopKeys = "stopCount",
            currencyKeys = "currency",
            linkKeys = "deepLink",
            minPrice = 10_000
        )

        val result = widened.extract(
            objectMapper.readTree("""{ "airlineName": "에어부산", "brandNewFareField": 279000 }""")
        )

        assertEquals(279_000, result.offers.single().price)
        assertEquals(listOf("brandNewFareField"), result.matchedPriceKeys)
    }
}

package com.sionicai.chatbot.flight.client

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDate

/**
 * FlightSearchClient 전략 패턴 테스트.
 * `flight.provider` 설정으로 구현체가 교체되는지, Mock 구현체가 리포트에 쓸 만한 값을 내는지 확인합니다.
 */
class FlightSearchClientStrategyTest {

    private val client: FlightSearchClient = MockFlightSearchClient()

    private val query = FlightSearchQuery(
        origin = "ICN",
        destination = "NRT",
        departureDate = LocalDate.now().plusMonths(2)
    )

    @Test
    @DisplayName("Mock 구현체는 인터페이스 계약을 지킨다")
    fun `mock client honours the interface contract`() {
        assertEquals("mock", client.providerName)

        val offers = client.search(query)
        assertTrue(offers.isNotEmpty())
        assertEquals(offers.sortedBy { it.price }, offers)
        assertTrue(offers.all { it.price > 0 })
        assertTrue(offers.all { it.currency == "KRW" })
    }

    @Test
    @DisplayName("같은 조건은 같은 날 항상 같은 가격을 낸다")
    fun `search is deterministic within a day`() {
        assertEquals(client.search(query), client.search(query))
    }

    @Test
    @DisplayName("노선이 다르면 가격도 달라진다")
    fun `different routes yield different prices`() {
        assertNotEquals(
            client.search(query).first().price,
            client.search(query.copy(destination = "CDG")).first().price
        )
    }

    @Test
    @DisplayName("왕복은 편도보다 비싸다")
    fun `round trip costs more than one way`() {
        val roundTrip = query.copy(returnDate = query.departureDate.plusDays(7))

        assertTrue(client.search(roundTrip).first().price > client.search(query).first().price)
    }

    @Test
    @DisplayName("인원수만큼 가격이 늘어난다")
    fun `price scales with passenger count`() {
        assertEquals(
            client.search(query).first().price * 3,
            client.search(query.copy(adults = 3)).first().price
        )
    }

    @Test
    @DisplayName("기본 diagnose 구현은 검색 결과를 그대로 담아 준다")
    fun `default diagnose wraps search results`() {
        val diagnostics = client.diagnose(query)

        assertEquals("mock", diagnostics.provider)
        assertNull(diagnostics.error)
        assertTrue(diagnostics.offers.isNotEmpty())
    }

    @SpringBootTest(properties = ["flight.provider=mock"])
    @Nested
    inner class MockProviderContext {
        @Autowired private lateinit var injected: FlightSearchClient

        @Test
        @DisplayName("flight.provider=mock 이면 Mock 구현체가 주입된다")
        fun `mock provider is wired`() {
            assertInstanceOf(MockFlightSearchClient::class.java, injected)
        }
    }

    @SpringBootTest(properties = ["flight.provider=naver"])
    @Nested
    inner class NaverProviderContext {
        @Autowired private lateinit var injected: FlightSearchClient

        /**
         * 컨텍스트가 뜨는 것 자체가 검증입니다.
         * `flight.naver.*` 설정 키나 템플릿 리소스가 하나라도 없으면 기동 단계에서 실패합니다.
         * (실제 네이버 호출은 하지 않습니다.)
         */
        @Test
        @DisplayName("flight.provider=naver 이면 Naver 구현체가 설정과 함께 주입된다")
        fun `naver provider is wired`() {
            assertInstanceOf(NaverFlightSearchClient::class.java, injected)
            assertEquals("naver", injected.providerName)
        }
    }
}

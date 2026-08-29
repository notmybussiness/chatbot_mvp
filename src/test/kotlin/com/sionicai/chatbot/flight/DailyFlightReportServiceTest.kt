package com.sionicai.chatbot.flight

import com.sionicai.chatbot.chat.repository.ChatRepository
import com.sionicai.chatbot.chat.repository.ThreadRepository
import com.sionicai.chatbot.flight.entity.FlightWatch
import com.sionicai.chatbot.flight.entity.PriceSnapshot
import com.sionicai.chatbot.flight.repository.FlightWatchRepository
import com.sionicai.chatbot.flight.repository.PriceSnapshotRepository
import com.sionicai.chatbot.flight.service.DailyFlightReportService
import com.sionicai.chatbot.user.entity.User
import com.sionicai.chatbot.user.entity.UserRole
import com.sionicai.chatbot.user.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 리포트 계산 로직(직전 대비 등락, 최근 최저가, 목표가 도달) 검증.
 * 외부 조회 없이 스냅샷을 직접 넣어 과거 데이터가 쌓인 상황을 재현합니다.
 */
@SpringBootTest(properties = ["flight.provider=mock", "flight.collection.request-delay-ms=0"])
class DailyFlightReportServiceTest {

    @Autowired private lateinit var service: DailyFlightReportService
    @Autowired private lateinit var flightWatchRepository: FlightWatchRepository
    @Autowired private lateinit var priceSnapshotRepository: PriceSnapshotRepository
    @Autowired private lateinit var chatRepository: ChatRepository
    @Autowired private lateinit var threadRepository: ThreadRepository
    @Autowired private lateinit var userRepository: UserRepository

    private lateinit var userId: UUID
    private lateinit var watchId: UUID

    private val today: LocalDate = LocalDate.now()

    @BeforeEach
    fun setUp() {
        priceSnapshotRepository.deleteAll()
        flightWatchRepository.deleteAll()
        chatRepository.deleteAll()
        threadRepository.deleteAll()
        userRepository.deleteAll()

        val user = userRepository.save(
            User(email = "report@flight.com", password = "encoded", name = "Reporter", role = UserRole.MEMBER)
        )
        userId = user.id!!

        watchId = flightWatchRepository.save(
            FlightWatch(
                user = user,
                origin = "ICN",
                destination = "CDG",
                departureDate = today.plusMonths(3),
                targetPrice = 900_000
            )
        ).id!!
    }

    private fun snapshot(daysAgo: Long, price: Long) {
        priceSnapshotRepository.save(
            PriceSnapshot(
                watchId = watchId,
                collectedOn = today.minusDays(daysAgo),
                lowestPrice = price,
                currency = "KRW",
                airline = "대한항공",
                stops = 0,
                offerCount = 4,
                source = "test"
            )
        )
    }

    private fun singleItem() = service.buildReportForUser(userId, today).items.single()

    @Test
    @DisplayName("직전 대비 하락폭과 하락률을 계산한다")
    fun `computes drop amount and rate`() {
        snapshot(daysAgo = 1, price = 500_000)
        snapshot(daysAgo = 0, price = 440_000)

        val item = singleItem()

        assertEquals(440_000, item.todayPrice)
        assertEquals(500_000, item.previousPrice)
        assertEquals(-60_000, item.changeAmount)
        assertTrue(abs(item.changeRate!! - -0.12) < 1e-9)
    }

    @Test
    @DisplayName("전일 데이터가 없으면 가장 최근 수집일과 비교한다")
    fun `falls back to the most recent earlier snapshot`() {
        // 3일 전 이후 수집이 끊겼다가 오늘 다시 수집된 상황
        snapshot(daysAgo = 3, price = 700_000)
        snapshot(daysAgo = 0, price = 770_000)

        val item = singleItem()

        assertEquals(700_000, item.previousPrice)
        assertEquals(70_000, item.changeAmount)
    }

    @Test
    @DisplayName("오늘이 최근 최저가면 isLowestInWindow 가 true 다")
    fun `flags today as the lowest in window`() {
        snapshot(daysAgo = 5, price = 600_000)
        snapshot(daysAgo = 0, price = 550_000)

        val item = singleItem()

        assertEquals(550_000, item.lowestInWindow)
        assertTrue(item.isLowestInWindow)
    }

    @Test
    @DisplayName("과거에 더 싼 기록이 있으면 isLowestInWindow 가 false 다")
    fun `does not flag lowest when a cheaper record exists`() {
        snapshot(daysAgo = 5, price = 480_000)
        snapshot(daysAgo = 0, price = 550_000)

        val item = singleItem()

        assertEquals(480_000, item.lowestInWindow)
        assertFalse(item.isLowestInWindow)
    }

    @Test
    @DisplayName("설정된 기간보다 오래된 기록은 최저가 계산에서 제외된다")
    fun `ignores snapshots older than the window`() {
        snapshot(daysAgo = 45, price = 300_000)
        snapshot(daysAgo = 2, price = 620_000)
        snapshot(daysAgo = 0, price = 610_000)

        val item = singleItem()

        // 45일 전 30만원은 30일 창 밖이므로 무시되어야 합니다.
        assertEquals(610_000, item.lowestInWindow)
        assertTrue(item.isLowestInWindow)
    }

    @Test
    @DisplayName("목표가 이하로 떨어지면 targetReached 가 true 다")
    fun `marks target as reached`() {
        snapshot(daysAgo = 0, price = 880_000)

        val item = singleItem()

        assertEquals(900_000, item.targetPrice)
        assertTrue(item.targetReached)
    }

    @Test
    @DisplayName("목표가보다 비싸면 targetReached 가 false 다")
    fun `does not mark target when price is higher`() {
        snapshot(daysAgo = 0, price = 950_000)

        assertFalse(singleItem().targetReached)
    }

    @Test
    @DisplayName("오늘 가격이 없으면 비어 있고 missingCount 에 잡힌다")
    fun `counts routes missing today price`() {
        snapshot(daysAgo = 1, price = 500_000)

        val report = service.buildReportForUser(userId, today)
        val item = report.items.single()

        assertNull(item.todayPrice)
        assertNull(item.changeAmount)
        assertEquals(1, report.missingCount)
    }

    @Test
    @DisplayName("과거 날짜 기준으로도 리포트를 다시 만들 수 있다")
    fun `rebuilds a report for a past date`() {
        snapshot(daysAgo = 3, price = 700_000)
        snapshot(daysAgo = 2, price = 650_000)
        snapshot(daysAgo = 0, price = 800_000)

        val item = service.buildReportForUser(userId, today.minusDays(2)).items.single()

        // 이틀 전 시점에서는 아직 오늘(80만원) 데이터가 없어야 합니다.
        assertEquals(650_000, item.todayPrice)
        assertEquals(700_000, item.previousPrice)
        assertEquals(650_000, item.lowestInWindow)
    }

    @Test
    @DisplayName("추적 노선이 없으면 빈 리포트를 반환한다")
    fun `returns an empty report without watches`() {
        flightWatchRepository.deleteAll()

        val report = service.buildReportForUser(userId, today)

        assertTrue(report.items.isEmpty())
        assertEquals(0, report.watchCount)
    }
}

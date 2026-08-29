package com.sionicai.chatbot.flight

import com.fasterxml.jackson.databind.ObjectMapper
import com.sionicai.chatbot.chat.repository.ChatRepository
import com.sionicai.chatbot.chat.repository.ThreadRepository
import com.sionicai.chatbot.common.security.JwtUtil
import com.sionicai.chatbot.flight.dto.CreateFlightWatchRequest
import com.sionicai.chatbot.flight.dto.UpdateFlightWatchRequest
import com.sionicai.chatbot.flight.entity.FlightWatch
import com.sionicai.chatbot.flight.repository.FlightWatchRepository
import com.sionicai.chatbot.flight.repository.PriceSnapshotRepository
import com.sionicai.chatbot.user.entity.User
import com.sionicai.chatbot.user.entity.UserRole
import com.sionicai.chatbot.user.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "flight.provider=mock",
        // 배치 사이 대기를 없애 테스트를 빠르게 유지합니다.
        "flight.collection.request-delay-ms=0"
    ]
)
@AutoConfigureMockMvc
class FlightIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var flightWatchRepository: FlightWatchRepository
    @Autowired private lateinit var priceSnapshotRepository: PriceSnapshotRepository
    @Autowired private lateinit var chatRepository: ChatRepository
    @Autowired private lateinit var threadRepository: ThreadRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var jwtUtil: JwtUtil

    private lateinit var memberId: UUID
    private lateinit var otherId: UUID
    private lateinit var adminId: UUID
    private lateinit var memberToken: String
    private lateinit var adminToken: String

    private val departureDate: LocalDate = LocalDate.now().plusMonths(2)

    @BeforeEach
    fun setUp() {
        priceSnapshotRepository.deleteAll()
        flightWatchRepository.deleteAll()
        chatRepository.deleteAll()
        threadRepository.deleteAll()
        userRepository.deleteAll()

        val member = userRepository.save(
            User(email = "member@flight.com", password = "encoded", name = "Member", role = UserRole.MEMBER)
        )
        val other = userRepository.save(
            User(email = "other@flight.com", password = "encoded", name = "Other", role = UserRole.MEMBER)
        )
        val admin = userRepository.save(
            User(email = "admin@flight.com", password = "encoded", name = "Admin", role = UserRole.ADMIN)
        )

        memberId = member.id!!
        otherId = other.id!!
        adminId = admin.id!!

        memberToken = "Bearer " + jwtUtil.generateToken(memberId, member.email, "ROLE_MEMBER")
        adminToken = "Bearer " + jwtUtil.generateToken(adminId, admin.email, "ROLE_ADMIN")
    }

    private fun createWatchFor(userId: UUID, targetPrice: Long? = null): FlightWatch {
        val user = userRepository.findById(userId).orElseThrow()
        return flightWatchRepository.save(
            FlightWatch(
                user = user,
                origin = "ICN",
                destination = "NRT",
                departureDate = departureDate,
                targetPrice = targetPrice
            )
        )
    }

    private fun runCollection() =
        mockMvc.perform(post("/api/flights/report/run").header(HttpHeaders.AUTHORIZATION, adminToken))

    private fun createWatchRequest(
        origin: String = "ICN",
        destination: String = "NRT",
        departure: LocalDate = departureDate,
        returnDate: LocalDate? = null
    ) = CreateFlightWatchRequest(
        origin = origin,
        destination = destination,
        departureDate = departure,
        returnDate = returnDate
    )

    // ---- 노선 등록/검증 ----

    @Test
    @DisplayName("추적 노선 생성 시 공항코드가 대문자로 정규화된다")
    fun `creates a watch and normalises iata codes`() {
        val request = createWatchRequest(origin = "icn", destination = "nrt")
            .copy(targetPrice = 300_000)

        mockMvc.perform(
            post("/api/flights/watches")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.origin").value("ICN"))
            .andExpect(jsonPath("$.data.destination").value("NRT"))
            .andExpect(jsonPath("$.data.active").value(true))
    }

    @Test
    @DisplayName("출발지와 도착지가 같으면 400 을 반환한다")
    fun `rejects identical origin and destination`() {
        mockMvc.perform(
            post("/api/flights/watches")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createWatchRequest(destination = "ICN")))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    @DisplayName("과거 출발일은 400 을 반환한다")
    fun `rejects past departure date`() {
        mockMvc.perform(
            post("/api/flights/watches")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createWatchRequest(departure = LocalDate.now().minusDays(1))))
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("귀국일이 출발일보다 빠르면 400 을 반환한다")
    fun `rejects return date before departure`() {
        mockMvc.perform(
            post("/api/flights/watches")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        createWatchRequest(returnDate = departureDate.minusDays(3))
                    )
                )
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("3자리가 아닌 공항코드는 400 을 반환한다")
    fun `rejects malformed iata code`() {
        mockMvc.perform(
            post("/api/flights/watches")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createWatchRequest(origin = "SEOUL")))
        )
            .andExpect(status().isBadRequest)
    }

    // ---- 권한 ----

    @Test
    @DisplayName("일반 사용자는 자신의 노선만 조회한다")
    fun `member sees only own watches`() {
        createWatchFor(memberId)
        createWatchFor(otherId)

        mockMvc.perform(get("/api/flights/watches").header(HttpHeaders.AUTHORIZATION, memberToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].userId").value(memberId.toString()))
    }

    @Test
    @DisplayName("관리자는 모든 노선을 조회한다")
    fun `admin sees every watch`() {
        createWatchFor(memberId)
        createWatchFor(otherId)

        mockMvc.perform(get("/api/flights/watches").header(HttpHeaders.AUTHORIZATION, adminToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.totalElements").value(2))
    }

    @Test
    @DisplayName("타인의 노선을 삭제하면 403 을 반환한다")
    fun `cannot delete another users watch`() {
        val watch = createWatchFor(otherId)

        mockMvc.perform(delete("/api/flights/watches/${watch.id}").header(HttpHeaders.AUTHORIZATION, memberToken))
            .andExpect(status().isForbidden)

        assertTrue(flightWatchRepository.existsById(watch.id!!))
    }

    @Test
    @DisplayName("일반 사용자는 리포트를 수동 실행할 수 없다")
    fun `member cannot trigger collection`() {
        mockMvc.perform(post("/api/flights/report/run").header(HttpHeaders.AUTHORIZATION, memberToken))
            .andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("일반 사용자는 진단으로 외부 조회를 유발할 수 없다")
    fun `member cannot run diagnostics`() {
        mockMvc.perform(
            post("/api/flights/diagnostics")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .param("origin", "ICN")
                .param("destination", "NRT")
                .param("departureDate", departureDate.toString())
        )
            .andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("인증 없이 접근하면 401 을 반환한다")
    fun `requires authentication`() {
        mockMvc.perform(get("/api/flights/watches")).andExpect(status().isUnauthorized)
    }

    // ---- 수집 / 리포트 ----

    @Test
    @DisplayName("수집 후 데일리 리포트에 오늘 가격이 담긴다")
    fun `daily report contains todays price after collection`() {
        createWatchFor(memberId, targetPrice = 10_000_000)

        runCollection()
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.succeeded").value(1))
            .andExpect(jsonPath("$.data.failed").value(0))

        mockMvc.perform(get("/api/flights/report/daily").header(HttpHeaders.AUTHORIZATION, memberToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.watchCount").value(1))
            .andExpect(jsonPath("$.data.missingCount").value(0))
            .andExpect(jsonPath("$.data.items[0].route").value("ICN → NRT"))
            .andExpect(jsonPath("$.data.items[0].todayPrice").isNumber)
            // 첫 수집이라 비교 대상이 없고, 오늘이 곧 최근 최저가입니다.
            .andExpect(jsonPath("$.data.items[0].previousPrice").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].isLowestInWindow").value(true))
            .andExpect(jsonPath("$.data.items[0].targetReached").value(true))
            .andExpect(jsonPath("$.data.summary").isNotEmpty)
    }

    @Test
    @DisplayName("수집 결과가 챗봇 스레드로 전달된다")
    fun `report is delivered to a chat thread`() {
        createWatchFor(memberId)

        runCollection().andExpect(status().isOk)

        val chats = chatRepository.findAll()
        assertEquals(1, chats.size)
        assertTrue(chats[0].question.contains("항공권 데일리 리포트"))
        assertTrue(chats[0].answer.contains("ICN → NRT"))

        // 리포트는 노선을 등록한 사용자의 새 스레드에 담겨야 합니다.
        val memberThreads = threadRepository.findAllByUserId(memberId, PageRequest.of(0, 10))
        assertEquals(1, memberThreads.totalElements)
        assertEquals(memberThreads.content[0].id, chats[0].thread.id)
    }

    @Test
    @DisplayName("같은 날 두 번 실행해도 스냅샷이 중복되지 않는다")
    fun `re-running on the same day does not duplicate snapshots`() {
        createWatchFor(memberId)

        runCollection().andExpect(status().isOk).andExpect(jsonPath("$.data.succeeded").value(1))
        runCollection().andExpect(status().isOk)
            .andExpect(jsonPath("$.data.succeeded").value(0))
            .andExpect(jsonPath("$.data.skipped").value(1))

        assertEquals(1, priceSnapshotRepository.count())
    }

    @Test
    @DisplayName("비활성 노선은 수집 대상에서 제외된다")
    fun `inactive watches are not collected`() {
        val watch = createWatchFor(memberId)
        watch.active = false
        flightWatchRepository.save(watch)

        runCollection().andExpect(status().isOk).andExpect(jsonPath("$.data.succeeded").value(0))

        assertEquals(0, priceSnapshotRepository.count())
    }

    @Test
    @DisplayName("목표가와 활성 여부를 수정한다")
    fun `updates target price and active flag`() {
        val watch = createWatchFor(memberId)
        val request = UpdateFlightWatchRequest(targetPrice = 250_000, active = false)

        mockMvc.perform(
            patch("/api/flights/watches/${watch.id}")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.targetPrice").value(250_000))
            .andExpect(jsonPath("$.data.active").value(false))
    }

    @Test
    @DisplayName("노선을 삭제하면 가격 이력도 함께 삭제된다")
    fun `deleting a watch removes its snapshots`() {
        val watch = createWatchFor(memberId)

        runCollection().andExpect(status().isOk)
        assertTrue(priceSnapshotRepository.count() > 0)

        mockMvc.perform(delete("/api/flights/watches/${watch.id}").header(HttpHeaders.AUTHORIZATION, memberToken))
            .andExpect(status().isOk)

        assertEquals(0, priceSnapshotRepository.count())
    }

    @Test
    @DisplayName("관리자 진단은 provider 와 조회 결과를 돌려준다")
    fun `admin diagnostics reports provider and offers`() {
        mockMvc.perform(
            post("/api/flights/diagnostics")
                .header(HttpHeaders.AUTHORIZATION, adminToken)
                .param("origin", "icn")
                .param("destination", "nrt")
                .param("departureDate", departureDate.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.provider").value("mock"))
            .andExpect(jsonPath("$.data.offers").isNotEmpty)
            .andExpect(jsonPath("$.data.error").doesNotExist())
    }
}

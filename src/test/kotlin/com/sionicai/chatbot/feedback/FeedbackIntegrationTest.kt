package com.sionicai.chatbot.feedback

import com.fasterxml.jackson.databind.ObjectMapper
import com.sionicai.chatbot.chat.entity.Chat
import com.sionicai.chatbot.chat.entity.Thread
import com.sionicai.chatbot.chat.repository.ChatRepository
import com.sionicai.chatbot.chat.repository.ThreadRepository
import com.sionicai.chatbot.feedback.dto.CreateFeedbackRequest
import com.sionicai.chatbot.feedback.dto.UpdateFeedbackStatusRequest
import com.sionicai.chatbot.feedback.entity.FeedbackStatus
import com.sionicai.chatbot.feedback.repository.FeedbackRepository
import com.sionicai.chatbot.common.security.JwtUtil
import com.sionicai.chatbot.user.entity.User
import com.sionicai.chatbot.user.entity.UserRole
import com.sionicai.chatbot.user.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class FeedbackIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var feedbackRepository: FeedbackRepository

    @Autowired
    private lateinit var chatRepository: ChatRepository

    @Autowired
    private lateinit var threadRepository: ThreadRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jwtUtil: JwtUtil

    private lateinit var memberId: UUID
    private lateinit var adminId: UUID
    private lateinit var memberChatId: UUID
    private lateinit var otherUserChatId: UUID
    private lateinit var memberToken: String
    private lateinit var adminToken: String

    @BeforeEach
    fun setUp() {
        feedbackRepository.deleteAll()
        chatRepository.deleteAll()
        threadRepository.deleteAll()
        userRepository.deleteAll()

        val member = userRepository.save(
            User(
                email = "member@test.com",
                password = "encoded",
                name = "Member",
                role = UserRole.MEMBER
            )
        )
        val admin = userRepository.save(
            User(
                email = "admin@test.com",
                password = "encoded",
                name = "Admin",
                role = UserRole.ADMIN
            )
        )
        val otherUser = userRepository.save(
            User(
                email = "other@test.com",
                password = "encoded",
                name = "Other",
                role = UserRole.MEMBER
            )
        )

        memberId = member.id!!
        adminId = admin.id!!

        val memberThread = threadRepository.save(Thread(user = member))
        val otherThread = threadRepository.save(Thread(user = otherUser))
        memberChatId = chatRepository.save(
            Chat(
                question = "member question",
                answer = "member answer",
                thread = memberThread
            )
        ).id!!
        otherUserChatId = chatRepository.save(
            Chat(
                question = "other question",
                answer = "other answer",
                thread = otherThread
            )
        ).id!!

        memberToken = "Bearer " + jwtUtil.generateToken(memberId, "member@test.com", "ROLE_MEMBER")
        adminToken = "Bearer " + jwtUtil.generateToken(adminId, "admin@test.com", "ROLE_ADMIN")
    }

    @Test
    fun `피드백 생성 성공`() {
        val request = CreateFeedbackRequest(chatId = memberChatId, isPositive = true)

        mockMvc.perform(
            post("/api/feedbacks")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.isPositive").value(true))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
    }

    @Test
    fun `같은 chatId에 중복 피드백 시 409 Conflict 반환`() {
        val request = CreateFeedbackRequest(chatId = memberChatId, isPositive = true)

        // First creation
        mockMvc.perform(
            post("/api/feedbacks")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isCreated)

        // Second creation should fail with 409
        mockMvc.perform(
            post("/api/feedbacks")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isConflict)
    }

    @Test
    fun `멤버는 타인 chat에 피드백 생성 불가 - 403`() {
        val request = CreateFeedbackRequest(chatId = otherUserChatId, isPositive = true)

        mockMvc.perform(
            post("/api/feedbacks")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `관리자는 모든 chat에 피드백 생성 가능`() {
        val request = CreateFeedbackRequest(chatId = otherUserChatId, isPositive = false)

        mockMvc.perform(
            post("/api/feedbacks")
                .header(HttpHeaders.AUTHORIZATION, adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.isPositive").value(false))
    }

    @Test
    fun `피드백 목록 조회 - 권한별 조회 확인`() {
        // Create an initial feedback
        val request = CreateFeedbackRequest(chatId = memberChatId, isPositive = true)
        mockMvc.perform(
            post("/api/feedbacks")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isCreated)

        // Member requests feedbacks -> should return 1 element
        mockMvc.perform(
            get("/api/feedbacks")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.content.length()").value(1))

        // Admin gets all feedbacks
        mockMvc.perform(
            get("/api/feedbacks")
                .header(HttpHeaders.AUTHORIZATION, adminToken)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.content.length()").value(1))
    }

    @Test
    fun `admin만 피드백 상태 변경 가능`() {
        // 1. Create feedback with member
        val request = CreateFeedbackRequest(chatId = memberChatId, isPositive = true)
        val mvcResult = mockMvc.perform(
            post("/api/feedbacks")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isCreated).andReturn()
        
        val jsonResponse = objectMapper.readTree(mvcResult.response.contentAsString)
        val feedbackId = jsonResponse.get("data").get("id").asText()

        // 2. Member tries to update -> 403
        val updateRequest = UpdateFeedbackStatusRequest(status = FeedbackStatus.RESOLVED)
        mockMvc.perform(
            patch("/api/feedbacks/$feedbackId/status")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest))
        ).andExpect(status().isForbidden)

        // 3. Admin tries to update -> 200
        mockMvc.perform(
            patch("/api/feedbacks/$feedbackId/status")
                .header(HttpHeaders.AUTHORIZATION, adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("RESOLVED"))
    }
}

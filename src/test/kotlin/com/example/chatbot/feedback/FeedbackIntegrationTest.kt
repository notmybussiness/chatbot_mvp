package com.example.chatbot.feedback

import com.fasterxml.jackson.databind.ObjectMapper
import com.example.chatbot.feedback.dto.CreateFeedbackRequest
import com.example.chatbot.feedback.dto.UpdateFeedbackStatusRequest
import com.example.chatbot.feedback.entity.FeedbackStatus
import com.example.chatbot.feedback.repository.FeedbackRepository
import com.example.chatbot.common.security.JwtUtil
import org.junit.jupiter.api.AfterEach
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
    private lateinit var jwtUtil: JwtUtil

    private val memberId = UUID.randomUUID()
    private val adminId = UUID.randomUUID()
    private val chatId = UUID.randomUUID()
    private lateinit var memberToken: String
    private lateinit var adminToken: String

    @BeforeEach
    fun setUp() {
        feedbackRepository.deleteAll()
        memberToken = "Bearer " + jwtUtil.generateToken(memberId, "member@test.com", "ROLE_MEMBER")
        adminToken = "Bearer " + jwtUtil.generateToken(adminId, "admin@test.com", "ROLE_ADMIN")
    }

    @AfterEach
    fun tearDown() {
        feedbackRepository.deleteAll()
    }

    @Test
    fun `피드백 생성 성공`() {
        val request = CreateFeedbackRequest(chatId = chatId, isPositive = true)

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
        val request = CreateFeedbackRequest(chatId = chatId, isPositive = true)

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
    fun `피드백 목록 조회 - 권한별 조회 확인`() {
        // Create an initial feedback
        val request = CreateFeedbackRequest(chatId = chatId, isPositive = true)
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
        val request = CreateFeedbackRequest(chatId = chatId, isPositive = true)
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

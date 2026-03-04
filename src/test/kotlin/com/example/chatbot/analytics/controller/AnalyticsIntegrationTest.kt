package com.example.chatbot.analytics.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.example.chatbot.analytics.adapter.AnalyticsPort
import com.example.chatbot.analytics.dto.ChatReportDto
import com.example.chatbot.analytics.entity.LoginLog
import com.example.chatbot.analytics.repository.LoginLogRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalyticsIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var loginLogRepository: LoginLogRepository

    @MockBean
    private lateinit var analyticsPort: AnalyticsPort

    @BeforeEach
    fun setup() {
        loginLogRepository.deleteAll()
        loginLogRepository.save(LoginLog(userId = java.util.UUID.randomUUID()))
        loginLogRepository.save(LoginLog(userId = java.util.UUID.randomUUID()))
    }

    @Test
    @DisplayName("Admin 활동 기록 요청 성공")
    @WithMockUser(roles = ["ADMIN"])
    fun `admin should get activity stats`() {
        // Stubbing AnalyticsPort methods
        whenever(analyticsPort.getSignUpCountSince(any())).thenReturn(5L)
        whenever(analyticsPort.getChatCountSince(any())).thenReturn(10L)

        mockMvc.perform(get("/api/analytics/activity")
            .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.signUpCount").value(5))
            .andExpect(jsonPath("$.data.loginCount").value(2))
            .andExpect(jsonPath("$.data.chatCount").value(10))
    }

    @Test
    @DisplayName("Member 활동 기록 요청 실패 (Forbidden)")
    @WithMockUser(roles = ["MEMBER"])
    fun `member should not be able to get activity stats`() {
        mockMvc.perform(get("/api/analytics/activity"))
            .andExpect(status().isForbidden)
    }

    @Test
    @DisplayName("Admin 리포트 다운로드 요청 성공 (CSV 형태 및 헤더 검증)")
    @WithMockUser(roles = ["ADMIN"])
    fun `admin should download csv report`() {
        val mockData = listOf(
            ChatReportDto(
                chatId = UUID.randomUUID(),
                question = "How are you?",
                answer = "I'm fine \"thanks\"",
                createdAt = OffsetDateTime.now(),
                userEmail = "user@test.com",
                userName = "Test User"
            )
        )
        whenever(analyticsPort.getChatReportDataSince(any())).thenReturn(mockData)

        val result = mockMvc.perform(get("/api/analytics/report"))
            .andExpect(status().isOk)
            .andExpect(header().exists(HttpHeaders.CONTENT_DISPOSITION))
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andReturn()
            
        val contentBody = result.response.contentAsString
        // check BOM and headers
        assertTrue(contentBody.contains("chat_id,question,answer,created_at,user_email,user_name"))
        // check escaped response
        assertTrue(contentBody.contains("\"I'm fine \"\"thanks\"\"\""))
    }

    @Test
    @DisplayName("Member 리포트 다운로드 요청 실패 (Forbidden)")
    @WithMockUser(roles = ["MEMBER"])
    fun `member should not be able to download csv report`() {
        mockMvc.perform(get("/api/analytics/report"))
            .andExpect(status().isForbidden)
    }
}

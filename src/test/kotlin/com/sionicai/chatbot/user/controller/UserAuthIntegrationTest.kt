package com.sionicai.chatbot.user.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.sionicai.chatbot.analytics.repository.LoginLogRepository
import com.sionicai.chatbot.chat.repository.ChatRepository
import com.sionicai.chatbot.chat.repository.ThreadRepository
import com.sionicai.chatbot.feedback.repository.FeedbackRepository
import com.sionicai.chatbot.user.dto.LoginRequest
import com.sionicai.chatbot.user.dto.SignUpRequest
import com.sionicai.chatbot.flight.repository.FlightWatchRepository
import com.sionicai.chatbot.flight.repository.PriceSnapshotRepository
import com.sionicai.chatbot.user.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class UserAuthIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var threadRepository: ThreadRepository

    @Autowired
    private lateinit var chatRepository: ChatRepository

    @Autowired
    private lateinit var feedbackRepository: FeedbackRepository

    @Autowired
    private lateinit var loginLogRepository: LoginLogRepository

    @Autowired
    private lateinit var flightWatchRepository: FlightWatchRepository

    @Autowired
    private lateinit var priceSnapshotRepository: PriceSnapshotRepository

    @BeforeEach
    fun setUp() {
        feedbackRepository.deleteAll()
        loginLogRepository.deleteAll()
        chatRepository.deleteAll()
        threadRepository.deleteAll()
        priceSnapshotRepository.deleteAll()
        flightWatchRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `회원가입 성공 - 201`() {
        val request = SignUpRequest("test@email.com", "password", "tester")

        mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").exists()) // UUID 확인
    }

    @Test
    fun `중복 이메일 회원가입 - 409`() {
        val request = SignUpRequest("test@email.com", "password", "tester")
        
        // 첫 번째 가입 성공
        mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        ).andExpect(status().isCreated)

        // 두 번째 가입 실패 (Conflict)
        mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").isString)
    }

    @Test
    fun `로그인 성공 - 200 + JWT`() {
        val signUpRequest = SignUpRequest("test@email.com", "password", "tester")
        mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signUpRequest))
        ).andExpect(status().isCreated)

        val loginRequest = LoginRequest("test@email.com", "password")

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.token").exists())
            .andExpect(jsonPath("$.data.email").value("test@email.com"))
            .andExpect(jsonPath("$.data.name").value("tester"))
            .andExpect(jsonPath("$.data.role").value("MEMBER"))
    }

    @Test
    fun `잘못된 비밀번호 - 401`() {
        val signUpRequest = SignUpRequest("test@email.com", "password", "tester")
        mockMvc.perform(
            post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signUpRequest))
        ).andExpect(status().isCreated)

        val loginRequest = LoginRequest("test@email.com", "wrong_password")

        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest))
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").isString)
    }

    @Test
    fun `인가되지 않은 사용자의 보호 API 접근 - 401`() {
         mockMvc.perform(
            get("/api/chats/threads")
        )
            .andExpect(status().isUnauthorized)
    }
}

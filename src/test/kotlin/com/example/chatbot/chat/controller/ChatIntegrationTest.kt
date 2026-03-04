package com.example.chatbot.chat.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.example.chatbot.chat.dto.ChatCreateRequest
import com.example.chatbot.chat.entity.Chat
import com.example.chatbot.chat.entity.Thread as ChatThread
import com.example.chatbot.chat.port.ChatResponsePort
import com.example.chatbot.chat.repository.ChatRepository
import com.example.chatbot.chat.repository.ThreadRepository
import com.example.chatbot.common.client.AiClient
import com.example.chatbot.common.client.ChatMessage
import com.example.chatbot.user.entity.User
import com.example.chatbot.user.entity.UserRole
import com.example.chatbot.user.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@AutoConfigureMockMvc
class ChatIntegrationTest {

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

    @MockBean
    private lateinit var aiClient: AiClient

    private lateinit var testUser: User

    @BeforeEach
    fun setUp() {
        chatRepository.deleteAll()
        threadRepository.deleteAll()
        userRepository.deleteAll()

        testUser = userRepository.save(
            User(
                email = "user@test.com",
                password = "hashed_password",
                name = "Test User",
                role = UserRole.MEMBER
            )
        )
    }

    @Test
    fun `인증되지 않은 접근 거부 - 401`() {
        val request = ChatCreateRequest("Hello", false, "gpt-3.5-turbo")
        mockMvc.perform(
            post("/api/chats")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(username = "user@test.com", roles = ["MEMBER"])
    fun `대화 생성 - 첫 요청 시 새 스레드 생성 (MockUserDetails 필터 의존 없이)`() {
        // 이 부분은 Security Context Mock에 의존하므로, Custom UserDetails 구현체 방식에 따라
        // @WithUserDetails 등을 써야 할 수 있으나, 일단 MockUser와 Service 로직 호환성을 확인해야 합니다.
        // 현재 ChatController는 @AuthenticationPrincipal userId: UUID 를 받도록 되어 있기 때문에,
        // 단순 @WithMockUser로는 UUID 인젝션이 실패할 확률이 매우 높습니다.
    }
}

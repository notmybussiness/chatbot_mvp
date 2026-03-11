package com.sionicai.chatbot.chat.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sionicai.chatbot.chat.dto.ChatCreateRequest
import com.sionicai.chatbot.chat.repository.ChatRepository
import com.sionicai.chatbot.chat.repository.ThreadRepository
import com.sionicai.chatbot.common.client.AiClient
import com.sionicai.chatbot.common.security.JwtUtil
import com.sionicai.chatbot.user.entity.User
import com.sionicai.chatbot.user.entity.UserRole
import com.sionicai.chatbot.user.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

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

    @Autowired
    private lateinit var jwtUtil: JwtUtil

    @MockBean
    private lateinit var aiClient: AiClient

    private lateinit var memberToken: String
    private lateinit var otherMemberToken: String
    private lateinit var adminToken: String

    @BeforeEach
    fun setUp() {
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
        val otherMember = userRepository.save(
            User(
                email = "other@test.com",
                password = "encoded",
                name = "Other Member",
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

        memberToken = bearerToken(member.id!!, member.email, member.role)
        otherMemberToken = bearerToken(otherMember.id!!, otherMember.email, otherMember.role)
        adminToken = bearerToken(admin.id!!, admin.email, admin.role)
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
    fun `비스트리밍 대화 생성 성공`() {
        whenever(aiClient.chatCompletion(any(), anyOrNull())).thenReturn("AI answer")

        mockMvc.perform(
            post("/api/chats")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ChatCreateRequest("질문", false, "gpt-4o-mini")))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.answer").value("AI answer"))
            .andExpect(jsonPath("$.data.isCompleted").value(true))
    }

    @Test
    fun `30분 이내 재질문 시 기존 스레드 재사용`() {
        whenever(aiClient.chatCompletion(any(), anyOrNull())).thenReturn("AI answer")

        val first = createChat(memberToken, ChatCreateRequest("첫 질문", false, null))
        val second = createChat(memberToken, ChatCreateRequest("두 번째 질문", false, null))

        val firstThreadId = getDataNode(first).get("threadId").asText()
        val secondThreadId = getDataNode(second).get("threadId").asText()

        org.junit.jupiter.api.Assertions.assertEquals(firstThreadId, secondThreadId)
    }

    @Test
    fun `스레드 목록 조회 시 대화가 스레드 단위로 그룹화된다`() {
        whenever(aiClient.chatCompletion(any(), anyOrNull())).thenReturn("AI answer")
        createChat(memberToken, ChatCreateRequest("Q1", false, null))
        createChat(memberToken, ChatCreateRequest("Q2", false, null))

        mockMvc.perform(
            get("/api/chats/threads")
                .header(HttpHeaders.AUTHORIZATION, memberToken)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.content.length()").value(1))
            .andExpect(jsonPath("$.data.content[0].chats.length()").value(2))
            .andExpect(jsonPath("$.data.content[0].chats[0].question").value("Q1"))
            .andExpect(jsonPath("$.data.content[0].chats[1].question").value("Q2"))
    }

    @Test
    fun `스트리밍 모드 생성 후 상태 조회로 완료 확인`() {
        whenever(aiClient.chatCompletion(any(), anyOrNull())).thenReturn("Streaming answer")
        val createResult = createChat(memberToken, ChatCreateRequest("stream question", true, null))
        val chatId = getDataNode(createResult).get("chatId").asText()

        var completed = false
        repeat(10) {
            val statusResult = mockMvc.perform(
                get("/api/chats/$chatId/status")
                    .header(HttpHeaders.AUTHORIZATION, memberToken)
            )
                .andExpect(status().isOk)
                .andReturn()
            val data = getDataNode(statusResult)
            completed = data.get("isCompleted").asBoolean()
            if (completed) {
                org.junit.jupiter.api.Assertions.assertEquals("Streaming answer", data.get("answer").asText())
                return
            }
            Thread.sleep(50)
        }

        org.junit.jupiter.api.Assertions.assertTrue(completed)
    }

    @Test
    fun `다른 사용자의 채팅 상태 조회 불가`() {
        whenever(aiClient.chatCompletion(any(), anyOrNull())).thenReturn("AI answer")
        val createResult = createChat(memberToken, ChatCreateRequest("private question", false, null))
        val chatId = getDataNode(createResult).get("chatId").asText()

        mockMvc.perform(
            get("/api/chats/$chatId/status")
                .header(HttpHeaders.AUTHORIZATION, otherMemberToken)
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `관리자는 전체 스레드 조회 가능`() {
        whenever(aiClient.chatCompletion(any(), anyOrNull())).thenReturn("AI answer")
        createChat(memberToken, ChatCreateRequest("member question", false, null))
        createChat(otherMemberToken, ChatCreateRequest("other question", false, null))

        mockMvc.perform(
            get("/api/chats/threads")
                .header(HttpHeaders.AUTHORIZATION, adminToken)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.content.length()").value(2))
    }

    @Test
    fun `스레드 삭제는 소유자 또는 관리자만 가능`() {
        whenever(aiClient.chatCompletion(any(), anyOrNull())).thenReturn("AI answer")
        val createResult = createChat(memberToken, ChatCreateRequest("delete question", false, null))
        val threadId = getDataNode(createResult).get("threadId").asText()

        mockMvc.perform(
            delete("/api/chats/threads/$threadId")
                .header(HttpHeaders.AUTHORIZATION, otherMemberToken)
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            delete("/api/chats/threads/$threadId")
                .header(HttpHeaders.AUTHORIZATION, adminToken)
        ).andExpect(status().isOk)
    }

    private fun createChat(token: String, request: ChatCreateRequest): MvcResult {
        return mockMvc.perform(
            post("/api/chats")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isCreated)
            .andReturn()
    }

    private fun getDataNode(result: MvcResult): JsonNode {
        return objectMapper.readTree(result.response.contentAsString).get("data")
    }

    private fun bearerToken(userId: java.util.UUID, email: String, role: UserRole): String {
        val token = jwtUtil.generateToken(userId, email, role.name)
        return "Bearer $token"
    }
}

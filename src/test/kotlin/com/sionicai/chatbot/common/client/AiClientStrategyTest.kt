package com.sionicai.chatbot.common.client

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * AiClient Strategy 패턴 단위 테스트.
 * MockAiClient의 동작을 검증합니다.
 */
class AiClientStrategyTest {

    private val mockClient: AiClient = MockAiClient()

    @Test
    @DisplayName("MockAiClient는 AiClient 인터페이스를 구현한다")
    fun `mock client implements AiClient interface`() {
        assertTrue(mockClient is AiClient)
    }

    @Test
    @DisplayName("MockAiClient는 마지막 사용자 메시지를 포함한 응답을 반환한다")
    fun `mock client returns response containing user message`() {
        val messages = listOf(
            ChatMessage("user", "Hello, how are you?")
        )

        val response = mockClient.chatCompletion(messages)

        assertNotNull(response)
        assertTrue(response.contains("Hello, how are you?"))
        assertTrue(response.contains("Mock"))
    }

    @Test
    @DisplayName("MockAiClient는 지정된 모델 이름을 응답에 포함한다")
    fun `mock client includes specified model in response`() {
        val messages = listOf(
            ChatMessage("user", "Test")
        )

        val response = mockClient.chatCompletion(messages, "gpt-4")

        assertTrue(response.contains("gpt-4"))
    }

    @Test
    @DisplayName("MockAiClient는 모델 미지정 시 기본 모델을 사용한다")
    fun `mock client uses default model when not specified`() {
        val messages = listOf(
            ChatMessage("user", "Test")
        )

        val response = mockClient.chatCompletion(messages)

        assertTrue(response.contains("mock-default"))
    }

    @Test
    @DisplayName("MockAiClient는 빈 메시지 리스트도 처리한다")
    fun `mock client handles empty messages`() {
        val response = mockClient.chatCompletion(emptyList())

        assertNotNull(response)
        assertTrue(response.contains("empty"))
    }

    @Test
    @DisplayName("MockAiClient는 여러 메시지 중 마지막 user 메시지를 사용한다")
    fun `mock client uses last user message from conversation`() {
        val messages = listOf(
            ChatMessage("user", "First question"),
            ChatMessage("assistant", "First answer"),
            ChatMessage("user", "Second question")
        )

        val response = mockClient.chatCompletion(messages)

        assertTrue(response.contains("Second question"))
    }

    @Test
    @DisplayName("Strategy 패턴: 런타임에 구현체를 교체할 수 있다")
    fun `strategy pattern allows switching implementation at runtime`() {
        // Mock 구현체
        val mock: AiClient = MockAiClient()
        val mockResponse = mock.chatCompletion(listOf(ChatMessage("user", "Hi")))
        assertTrue(mockResponse.contains("Mock"))

        // 다른 구현체 (테스트용 커스텀)
        val custom: AiClient = object : AiClient {
            override fun chatCompletion(messages: List<ChatMessage>, model: String?): String {
                return "Custom response"
            }
        }
        val customResponse = custom.chatCompletion(listOf(ChatMessage("user", "Hi")))
        assertEquals("Custom response", customResponse)
    }
}

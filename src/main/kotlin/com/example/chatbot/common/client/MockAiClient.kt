package com.example.chatbot.common.client

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Mock AI 클라이언트 - API 키 없이도 시연 가능.
 * ai.provider=mock (기본값) 일 때 활성화.
 */
@Component
@ConditionalOnProperty(name = ["ai.provider"], havingValue = "mock", matchIfMissing = true)
class MockAiClient : AiClient {

    private val logger = LoggerFactory.getLogger(MockAiClient::class.java)

    override fun chatCompletion(messages: List<ChatMessage>, model: String?): String {
        val lastUserMessage = messages.lastOrNull { it.role == "user" }?.content ?: "empty"
        val usedModel = model ?: "mock-default"

        logger.info("[MockAI] Generating response for: \"$lastUserMessage\" (model: $usedModel)")

        return buildString {
            appendLine("안녕하세요! Mock AI 응답입니다.")
            appendLine()
            appendLine("📝 받은 질문: \"$lastUserMessage\"")
            appendLine("🤖 사용 모델: $usedModel")
            appendLine("💬 대화 히스토리: ${messages.size}개 메시지")
            appendLine()
            appendLine("이것은 시연용 Mock 응답입니다. 실제 AI 응답을 받으려면 ai.provider=openai로 설정하고 OPENAI_API_KEY를 제공하세요.")
        }
    }
}

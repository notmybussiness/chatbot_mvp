package com.example.chatbot.common.client

/**
 * AI 응답 생성을 위한 전략 인터페이스.
 * Mock / OpenAI / 향후 RAG 구현체를 쉽게 교체할 수 있도록 설계.
 */
interface AiClient {

    /**
     * 대화 메시지 리스트를 받아 AI 응답을 생성합니다.
     *
     * @param messages 대화 히스토리 (role: user/assistant/system)
     * @param model 사용할 모델 이름 (null이면 기본 모델)
     * @return AI 응답 텍스트
     */
    fun chatCompletion(messages: List<ChatMessage>, model: String? = null): String
}

/**
 * OpenAI API 호환 메시지 포맷
 */
data class ChatMessage(
    val role: String,    // "system", "user", "assistant"
    val content: String
)

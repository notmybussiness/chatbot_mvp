package com.sionicai.chatbot.common.client

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

/**
 * 실제 OpenAI API를 호출하는 클라이언트.
 * ai.provider=openai 일 때 활성화.
 */
@Component
@ConditionalOnProperty(name = ["ai.provider"], havingValue = "openai")
class OpenAiClient(
    @Value("\${ai.openai.api-key}") private val apiKey: String,
    @Value("\${ai.openai.model}") private val defaultModel: String,
    @Value("\${ai.openai.base-url}") private val baseUrl: String
) : AiClient {

    private val logger = LoggerFactory.getLogger(OpenAiClient::class.java)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer $apiKey")
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build()
    }

    override fun chatCompletion(messages: List<ChatMessage>, model: String?): String {
        val usedModel = model ?: defaultModel

        logger.info("[OpenAI] Calling chat/completions with model=$usedModel, messages=${messages.size}")

        val requestBody = OpenAiRequest(
            model = usedModel,
            messages = messages.map { OpenAiMessage(it.role, it.content) }
        )

        val response = webClient.post()
            .uri("/chat/completions")
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(OpenAiResponse::class.java)
            .block() ?: throw RuntimeException("OpenAI API returned null response")

        return response.choices.firstOrNull()?.message?.content
            ?: throw RuntimeException("OpenAI API returned no choices")
    }

    // ---- OpenAI API DTOs ----

    data class OpenAiRequest(
        val model: String,
        val messages: List<OpenAiMessage>,
        val temperature: Double = 0.7
    )

    data class OpenAiMessage(
        val role: String,
        val content: String
    )

    data class OpenAiResponse(
        val id: String,
        val choices: List<Choice>
    )

    data class Choice(
        val index: Int,
        val message: ResponseMessage,
        @JsonProperty("finish_reason") val finishReason: String?
    )

    data class ResponseMessage(
        val role: String,
        val content: String
    )
}

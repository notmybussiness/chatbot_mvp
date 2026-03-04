package com.sionicai.chatbot.chat.adapter

import com.sionicai.chatbot.chat.port.ChatResponsePort
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class PollingChatResponseAdapter : ChatResponsePort {
    private val answers = ConcurrentHashMap<UUID, String>()
    private val completed = ConcurrentHashMap<UUID, Boolean>()

    override fun sendPartialResponse(chatId: UUID, partialAnswer: String) {
        answers[chatId] = partialAnswer
        completed[chatId] = false
    }

    override fun sendCompleteResponse(chatId: UUID, fullAnswer: String) {
        answers[chatId] = fullAnswer
        completed[chatId] = true
    }

    override fun getIntermediateResponse(chatId: UUID): String? {
        return answers[chatId]
    }

    override fun isCompleted(chatId: UUID): Boolean {
        return completed[chatId] ?: false
    }
}

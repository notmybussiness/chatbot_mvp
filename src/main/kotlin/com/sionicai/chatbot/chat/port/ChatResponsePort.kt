package com.sionicai.chatbot.chat.port

import java.util.UUID

interface ChatResponsePort {
    fun sendPartialResponse(chatId: UUID, partialAnswer: String)
    fun sendCompleteResponse(chatId: UUID, fullAnswer: String)
    fun getIntermediateResponse(chatId: UUID): String?
    fun isCompleted(chatId: UUID): Boolean
}

package com.sionicai.chatbot.chat.dto

import java.time.OffsetDateTime
import java.util.UUID

data class ThreadChatResponse(
    val chatId: UUID,
    val question: String,
    val answer: String,
    val createdAt: OffsetDateTime
)

data class ThreadResponse(
    val threadId: UUID,
    val userId: UUID,
    val createdAt: OffsetDateTime,
    val chats: List<ThreadChatResponse>
)

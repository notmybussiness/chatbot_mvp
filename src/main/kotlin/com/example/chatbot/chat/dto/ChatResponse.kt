package com.example.chatbot.chat.dto

import java.time.OffsetDateTime
import java.util.UUID

data class ChatResponse(
    val chatId: UUID,
    val threadId: UUID,
    val answer: String,
    val isCompleted: Boolean,
    val createdAt: OffsetDateTime
)

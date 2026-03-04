package com.example.chatbot.analytics.dto

import java.time.OffsetDateTime
import java.util.UUID

data class ChatReportDto(
    val chatId: UUID,
    val question: String,
    val answer: String,
    val createdAt: OffsetDateTime,
    val userEmail: String,
    val userName: String
)

package com.example.chatbot.chat.dto

import java.time.OffsetDateTime
import java.util.UUID

data class ThreadResponse(
    val threadId: UUID,
    val createdAt: OffsetDateTime
)

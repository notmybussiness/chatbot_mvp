package com.example.chatbot.feedback.dto

import com.example.chatbot.feedback.entity.FeedbackStatus
import jakarta.validation.constraints.NotNull
import java.time.OffsetDateTime
import java.util.UUID

data class CreateFeedbackRequest(
    @field:NotNull val chatId: UUID,
    @field:NotNull val isPositive: Boolean
)

data class UpdateFeedbackStatusRequest(
    @field:NotNull val status: FeedbackStatus
)

data class FeedbackResponse(
    val id: UUID,
    val userId: UUID,
    val chatId: UUID,
    val isPositive: Boolean,
    val status: FeedbackStatus,
    val createdAt: OffsetDateTime
)

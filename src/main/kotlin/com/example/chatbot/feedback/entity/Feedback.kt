package com.example.chatbot.feedback.entity

import com.example.chatbot.common.entity.BaseEntity
import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(
    name = "feedbacks",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "chat_id"])]
)
class Feedback(
    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "chat_id", nullable = false)
    val chatId: UUID,

    @Column(name = "is_positive", nullable = false)
    val isPositive: Boolean,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: FeedbackStatus = FeedbackStatus.PENDING

) : BaseEntity() {
    // Persistable removed to fix getId clash
}

enum class FeedbackStatus { PENDING, RESOLVED }

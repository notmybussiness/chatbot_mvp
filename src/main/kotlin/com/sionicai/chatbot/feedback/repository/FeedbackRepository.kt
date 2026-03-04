package com.sionicai.chatbot.feedback.repository

import com.sionicai.chatbot.feedback.entity.Feedback
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface FeedbackRepository : JpaRepository<Feedback, UUID> {
    fun existsByUserIdAndChatId(userId: UUID, chatId: UUID): Boolean
    fun findAllByUserId(userId: UUID, pageable: Pageable): Page<Feedback>
    
    // Admin filtering
    fun findAllByIsPositive(isPositive: Boolean, pageable: Pageable): Page<Feedback>
    
    // User filtering
    fun findAllByUserIdAndIsPositive(userId: UUID, isPositive: Boolean, pageable: Pageable): Page<Feedback>
}

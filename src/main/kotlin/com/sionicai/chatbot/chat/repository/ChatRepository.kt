package com.sionicai.chatbot.chat.repository

import com.sionicai.chatbot.chat.entity.Chat
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChatRepository : JpaRepository<Chat, UUID> {
    fun findFirstByThreadUserIdOrderByCreatedAtDesc(userId: UUID): Chat?
    fun findByThreadIdOrderByCreatedAtAsc(threadId: UUID): List<Chat>
}

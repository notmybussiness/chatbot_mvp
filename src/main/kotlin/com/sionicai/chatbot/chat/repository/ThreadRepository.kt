package com.sionicai.chatbot.chat.repository

import com.sionicai.chatbot.chat.entity.Thread
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ThreadRepository : JpaRepository<Thread, UUID> {
    fun findAllByUserId(userId: UUID, pageable: Pageable): Page<Thread>
}

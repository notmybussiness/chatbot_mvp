package com.sionicai.chatbot.user.repository

import com.sionicai.chatbot.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    fun findByEmail(email: String): User?
    fun existsByEmail(email: String): Boolean
    // Analytics용: 특정 시간 이후 가입한 사용자 수
    fun countByCreatedAtAfter(after: OffsetDateTime): Long
}

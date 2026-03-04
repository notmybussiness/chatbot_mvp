package com.sionicai.chatbot.analytics.repository

import com.sionicai.chatbot.analytics.entity.LoginLog
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime
import java.util.UUID

interface LoginLogRepository : JpaRepository<LoginLog, UUID> {
    fun countByCreatedAtAfter(after: OffsetDateTime): Long
}

package com.sionicai.chatbot.analytics.entity

import com.sionicai.chatbot.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "login_logs")
class LoginLog(
    @Column(name = "user_id", nullable = false)
    val userId: UUID
) : BaseEntity()

package com.example.chatbot.common.entity

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

@MappedSuperclass
abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    open val id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP WITH TIME ZONE")
    open val createdAt: OffsetDateTime = OffsetDateTime.now()
}

package com.sionicai.chatbot.chat.entity

import com.sionicai.chatbot.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "chats")
class Chat(
    @Column(columnDefinition = "TEXT", nullable = false)
    val question: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    val answer: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thread_id", nullable = false)
    val thread: Thread
) : BaseEntity()

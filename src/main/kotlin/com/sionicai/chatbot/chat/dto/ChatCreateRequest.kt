package com.sionicai.chatbot.chat.dto

import jakarta.validation.constraints.NotBlank

data class ChatCreateRequest(
    @field:NotBlank(message = "Question cannot be blank")
    val question: String,
    val isStreaming: Boolean = false,
    val model: String? = null
)

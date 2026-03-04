package com.example.chatbot.user.dto

import java.util.UUID

data class LoginResponse(
    val token: String,
    val userId: UUID,
    val email: String,
    val name: String,
    val role: String
)

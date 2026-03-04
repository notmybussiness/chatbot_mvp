package com.sionicai.chatbot.user.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class SignUpRequest(
    @field:Email 
    val email: String,
    
    @field:NotBlank 
    val password: String,
    
    @field:NotBlank 
    val name: String
)

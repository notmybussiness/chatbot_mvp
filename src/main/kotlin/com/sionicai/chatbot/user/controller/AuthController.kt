package com.sionicai.chatbot.user.controller

import com.sionicai.chatbot.common.dto.ApiResponse
import com.sionicai.chatbot.user.dto.LoginRequest
import com.sionicai.chatbot.user.dto.LoginResponse
import com.sionicai.chatbot.user.dto.SignUpRequest
import com.sionicai.chatbot.user.service.UserService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/auth")
class AuthController(private val userService: UserService) {

    @PostMapping("/signup")
    fun signUp(@Valid @RequestBody request: SignUpRequest): ResponseEntity<ApiResponse<UUID>> {
        val user = userService.signUp(request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(user.id!!))
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<ApiResponse<LoginResponse>> {
        val response = userService.login(request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}

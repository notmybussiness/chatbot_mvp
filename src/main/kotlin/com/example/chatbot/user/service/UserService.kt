package com.example.chatbot.user.service

import com.example.chatbot.common.exception.DuplicateResourceException
import com.example.chatbot.common.exception.ResourceNotFoundException
import com.example.chatbot.common.exception.UnauthorizedException
import com.example.chatbot.common.security.JwtUtil
import com.example.chatbot.user.dto.LoginRequest
import com.example.chatbot.user.dto.LoginResponse
import com.example.chatbot.user.dto.SignUpRequest
import com.example.chatbot.user.entity.User
import com.example.chatbot.user.entity.UserRole
import com.example.chatbot.user.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtUtil: JwtUtil
) {
    @Transactional
    fun signUp(request: SignUpRequest): User {
        if (userRepository.existsByEmail(request.email)) {
            throw DuplicateResourceException("User", "email", request.email)
        }

        val encodedPassword = passwordEncoder.encode(request.password)
        val user = User(
            email = request.email,
            password = encodedPassword,
            name = request.name,
            role = UserRole.MEMBER
        )

        return userRepository.save(user)
    }

    @Transactional(readOnly = true)
    fun login(request: LoginRequest): LoginResponse {
        val user = userRepository.findByEmail(request.email)
            ?: throw UnauthorizedException("Invalid credentials")

        if (!passwordEncoder.matches(request.password, user.password)) {
            throw UnauthorizedException("Invalid credentials")
        }

        val token = jwtUtil.generateToken(user.id!!, user.email, user.role.name)

        return LoginResponse(
            token = token,
            userId = user.id!!,
            email = user.email,
            name = user.name,
            role = user.role.name
        )
    }
}

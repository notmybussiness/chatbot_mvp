package com.sionicai.chatbot.user.service

import com.sionicai.chatbot.common.exception.DuplicateResourceException
import com.sionicai.chatbot.common.exception.ResourceNotFoundException
import com.sionicai.chatbot.common.exception.UnauthorizedException
import com.sionicai.chatbot.common.security.JwtUtil
import com.sionicai.chatbot.user.dto.LoginRequest
import com.sionicai.chatbot.user.dto.LoginResponse
import com.sionicai.chatbot.user.dto.SignUpRequest
import com.sionicai.chatbot.user.entity.User
import com.sionicai.chatbot.user.entity.UserRole
import com.sionicai.chatbot.user.repository.UserRepository
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

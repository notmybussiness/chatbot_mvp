package com.sionicai.chatbot.chat.controller

import com.sionicai.chatbot.chat.dto.ChatCreateRequest
import com.sionicai.chatbot.chat.dto.ChatResponse
import com.sionicai.chatbot.chat.dto.ThreadResponse
import com.sionicai.chatbot.chat.service.ChatService
import com.sionicai.chatbot.common.dto.ApiResponse
import com.sionicai.chatbot.user.entity.UserRole
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/chats")
class ChatController(
    private val chatService: ChatService
) {

    private fun extractUserRole(authentication: Authentication): UserRole {
        val authority = authentication.authorities.firstOrNull()?.authority ?: "ROLE_MEMBER"
        return if (authority == "ROLE_ADMIN") UserRole.ADMIN else UserRole.MEMBER
    }

    @PostMapping
    fun createChat(
        @AuthenticationPrincipal userId: UUID,
        @Valid @RequestBody request: ChatCreateRequest
    ): ResponseEntity<ApiResponse<ChatResponse>> {
        val response = chatService.createChat(userId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response))
    }

    @GetMapping("/{chatId}/status")
    fun getChatStatus(
        @AuthenticationPrincipal userId: UUID,
        @PathVariable chatId: UUID
    ): ResponseEntity<ApiResponse<ChatResponse>> {
        val response = chatService.getChatStatus(chatId)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/threads")
    fun getThreads(
        @AuthenticationPrincipal userId: UUID,
        authentication: Authentication,
        @PageableDefault(sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable
    ): ResponseEntity<ApiResponse<Page<ThreadResponse>>> {
        val role = extractUserRole(authentication)
        val response = chatService.getThreads(userId, role, pageable)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @DeleteMapping("/threads/{threadId}")
    fun deleteThread(
        @AuthenticationPrincipal userId: UUID,
        authentication: Authentication,
        @PathVariable threadId: UUID
    ): ResponseEntity<ApiResponse<Unit?>> {
        val role = extractUserRole(authentication)
        chatService.deleteThread(userId, threadId, role)
        return ResponseEntity.ok(ApiResponse(success = true))
    }
}

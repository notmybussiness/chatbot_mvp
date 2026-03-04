package com.example.chatbot.feedback.controller

import com.example.chatbot.common.dto.ApiResponse
import com.example.chatbot.common.dto.PageResponse
import com.example.chatbot.feedback.dto.CreateFeedbackRequest
import com.example.chatbot.feedback.dto.FeedbackResponse
import com.example.chatbot.feedback.dto.UpdateFeedbackStatusRequest
import com.example.chatbot.feedback.service.FeedbackService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/feedbacks")
class FeedbackController(
    private val feedbackService: FeedbackService
) {

    @PostMapping
    fun createFeedback(
        @AuthenticationPrincipal userId: UUID,
        authentication: Authentication,
        @Valid @RequestBody request: CreateFeedbackRequest
    ): ResponseEntity<ApiResponse<FeedbackResponse>> {
        val role = authentication.authorities.firstOrNull()?.authority ?: "ROLE_MEMBER"
        val response = feedbackService.createFeedback(userId, role, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response))
    }

    @GetMapping
    fun listFeedbacks(
        @AuthenticationPrincipal userId: UUID,
        authentication: Authentication,
        @RequestParam(required = false) isPositive: Boolean?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "desc") sort: String
    ): ResponseEntity<ApiResponse<PageResponse<FeedbackResponse>>> {
        val role = authentication.authorities.firstOrNull()?.authority ?: "ROLE_MEMBER"
        val response = feedbackService.listFeedbacks(userId, role, isPositive, page, size, sort)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    fun updateStatus(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateFeedbackStatusRequest
    ): ResponseEntity<ApiResponse<FeedbackResponse>> {
        val response = feedbackService.updateStatus(id, request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}

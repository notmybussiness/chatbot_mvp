package com.sionicai.chatbot.feedback.service

import com.sionicai.chatbot.chat.repository.ChatRepository
import com.sionicai.chatbot.common.dto.PageResponse
import com.sionicai.chatbot.common.exception.DuplicateResourceException
import com.sionicai.chatbot.common.exception.ForbiddenException
import com.sionicai.chatbot.common.exception.ResourceNotFoundException
import com.sionicai.chatbot.feedback.dto.CreateFeedbackRequest
import com.sionicai.chatbot.feedback.dto.FeedbackResponse
import com.sionicai.chatbot.feedback.dto.UpdateFeedbackStatusRequest
import com.sionicai.chatbot.feedback.entity.Feedback
import com.sionicai.chatbot.feedback.repository.FeedbackRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class FeedbackService(
    private val feedbackRepository: FeedbackRepository,
    private val chatRepository: ChatRepository
) {
    @Transactional
    fun createFeedback(userId: UUID, role: String, request: CreateFeedbackRequest): FeedbackResponse {
        // 1. Duplicate check: userId + chatId combination must be unique
        if (feedbackRepository.existsByUserIdAndChatId(userId, request.chatId)) {
            throw DuplicateResourceException("Feedback", "userId and chatId", "$userId, ${request.chatId}")
        }

        val chat = chatRepository.findByIdOrNull(request.chatId)
            ?: throw ResourceNotFoundException("Chat", request.chatId)

        if (role != "ROLE_ADMIN" && chat.thread.user.id != userId) {
            throw ForbiddenException("Users can only create feedback for their own chats")
        }

        val feedback = feedbackRepository.save(
            Feedback(
                userId = userId,
                chatId = request.chatId,
                isPositive = request.isPositive
            )
        )
        
        return toResponse(feedback)
    }

    @Transactional(readOnly = true)
    fun listFeedbacks(
        userId: UUID, role: String,
        isPositive: Boolean?, page: Int, size: Int, sortDir: String
    ): PageResponse<FeedbackResponse> {
        val sort = if (sortDir.equals("asc", ignoreCase = true)) {
            Sort.by(Sort.Direction.ASC, "createdAt")
        } else {
            Sort.by(Sort.Direction.DESC, "createdAt")
        }
        val pageable = PageRequest.of(page, size, sort)

        val pageResult = if (role == "ROLE_ADMIN") {
            if (isPositive != null) {
                feedbackRepository.findAllByIsPositive(isPositive, pageable)
            } else {
                feedbackRepository.findAll(pageable)
            }
        } else {
            if (isPositive != null) {
                feedbackRepository.findAllByUserIdAndIsPositive(userId, isPositive, pageable)
            } else {
                feedbackRepository.findAllByUserId(userId, pageable)
            }
        }

        return PageResponse.of(
            content = pageResult.content.map { toResponse(it) },
            page = pageResult.number,
            size = pageResult.size,
            totalElements = pageResult.totalElements
        )
    }

    @Transactional
    fun updateStatus(feedbackId: UUID, request: UpdateFeedbackStatusRequest): FeedbackResponse {
        val feedback = feedbackRepository.findById(feedbackId)
            .orElseThrow { ResourceNotFoundException("Feedback", feedbackId) }
        
        feedback.status = request.status
        return toResponse(feedbackRepository.save(feedback))
    }

    private fun toResponse(feedback: Feedback): FeedbackResponse {
        return FeedbackResponse(
            id = feedback.id!!,
            userId = feedback.userId,
            chatId = feedback.chatId,
            isPositive = feedback.isPositive,
            status = feedback.status,
            createdAt = feedback.createdAt
        )
    }
}

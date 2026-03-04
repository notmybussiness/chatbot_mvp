package com.sionicai.chatbot.chat.service

import com.sionicai.chatbot.chat.dto.ChatCreateRequest
import com.sionicai.chatbot.chat.dto.ChatResponse
import com.sionicai.chatbot.chat.dto.ThreadResponse
import com.sionicai.chatbot.chat.entity.Chat
import com.sionicai.chatbot.chat.entity.Thread
import com.sionicai.chatbot.chat.port.ChatResponsePort
import com.sionicai.chatbot.chat.repository.ChatRepository
import com.sionicai.chatbot.chat.repository.ThreadRepository
import com.sionicai.chatbot.common.client.AiClient
import com.sionicai.chatbot.user.entity.UserRole
import com.sionicai.chatbot.user.repository.UserRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.http.HttpStatus
import com.sionicai.chatbot.common.exception.ResourceNotFoundException
import com.sionicai.chatbot.common.exception.ForbiddenException
import com.sionicai.chatbot.common.exception.BusinessException
@Service
class ChatService(
    private val chatRepository: ChatRepository,
    private val threadRepository: ThreadRepository,
    private val userRepository: UserRepository,
    private val aiClient: AiClient,
    private val chatResponsePort: ChatResponsePort
) {
    @Transactional
    fun createChat(userId: UUID, request: ChatCreateRequest): ChatResponse {
        val user = userRepository.findByIdOrNull(userId)
            ?: throw ResourceNotFoundException("User", userId)

        val lastChat = chatRepository.findFirstByThreadUserIdOrderByCreatedAtDesc(userId)
        val now = OffsetDateTime.now()

        val thread = if (lastChat == null || lastChat.createdAt.plusMinutes(30).isBefore(now)) {
            threadRepository.save(Thread(user = user))
        } else {
            lastChat.thread
        }

        // 초기 Chat 엔티티 생성 (빈 답변)
        val chat = chatRepository.save(Chat(
            question = request.question,
            answer = "Thinking...",
            thread = thread
        ))

        chatResponsePort.sendPartialResponse(chat.id!!, "Thinking...")

        // 해당 스레드의 이전 컨텍스트 가져오기
        val contextChats = chatRepository.findByThreadIdOrderByCreatedAtAsc(thread.id!!)
        val messages = contextChats.flatMap {
            listOf(
                com.sionicai.chatbot.common.client.ChatMessage("user", it.question),
                com.sionicai.chatbot.common.client.ChatMessage("assistant", it.answer)
            )
        } + com.sionicai.chatbot.common.client.ChatMessage("user", request.question)

        // 비동기 처리는 실제로 Spring Async 방식이나 코루틴을 적용할 수 있지만, 요구사항이 kotlin 1.9 + Spring boot 3.x이므로 
        // Thread를 활용하거나 CompletableFuture를 사용할 수 있다.
        // 현재는 동기로 바로 처리한 뒤 결과 갱신을 보여줄 수 있도록 구성.
        // 프롬프트 명세상 '폴링 기반 응답 스트리밍(진행 상태) 조회 구현'
        
        // --- 동기적 호출 모방 또는 실제 AI 연동 ---
        // (AiClient.chatCompletion 구현 방식에 따름. 동기 함수면 여기서 blocking됨)
        Thread {
            try {
                // AiClient 호출 연동
                val aiResponse = aiClient.chatCompletion(messages, request.model)
                
                // 완료 응답 처리
                chatResponsePort.sendCompleteResponse(chat.id!!, aiResponse)
                
                // 트랜잭션 분리로 엔티티 업데이트 (이건 추후 개선)
            } catch (e: Exception) {
                chatResponsePort.sendCompleteResponse(chat.id!!, "Error: ${e.message}")
            }
        }.start()

        return ChatResponse(
            chatId = chat.id!!,
            threadId = thread.id!!,
            answer = "Thinking...",
            isCompleted = false,
            createdAt = chat.createdAt
        )
    }

    @Transactional(readOnly = true)
    fun getChatStatus(chatId: UUID): ChatResponse {
        val chat = chatRepository.findByIdOrNull(chatId)
            ?: throw ResourceNotFoundException("Chat", chatId)
        
        val isCompleted = chatResponsePort.isCompleted(chatId)
        val answer = chatResponsePort.getIntermediateResponse(chatId) ?: chat.answer
        
        // 최종 완료 상태면 DB에 확정 반영하는 로직이 필요할 수 있지만 (현재 ReadOnly)
        return ChatResponse(
            chatId = chat.id!!,
            threadId = chat.thread.id!!,
            answer = answer,
            isCompleted = isCompleted,
            createdAt = chat.createdAt
        )
    }

    @Transactional(readOnly = true)
    fun getThreads(userId: UUID, role: UserRole, pageable: Pageable): Page<ThreadResponse> {
        val page = if (role == UserRole.ADMIN) {
            threadRepository.findAll(pageable)
        } else {
            threadRepository.findAllByUserId(userId, pageable)
        }
        return page.map { ThreadResponse(it.id!!, it.createdAt) }
    }

    @Transactional
    fun deleteThread(userId: UUID, threadId: UUID, role: UserRole) {
        val thread = threadRepository.findByIdOrNull(threadId)
            ?: throw ResourceNotFoundException("Thread", threadId)

        if (role != UserRole.ADMIN && thread.user.id != userId) {
            throw ForbiddenException("Access denied")
        }

        threadRepository.delete(thread)
    }
}

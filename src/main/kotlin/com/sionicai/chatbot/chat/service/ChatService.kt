package com.sionicai.chatbot.chat.service

import com.sionicai.chatbot.chat.dto.ChatCreateRequest
import com.sionicai.chatbot.chat.dto.ChatResponse
import com.sionicai.chatbot.chat.dto.ThreadResponse
import com.sionicai.chatbot.chat.dto.ThreadChatResponse
import com.sionicai.chatbot.chat.entity.Chat
import com.sionicai.chatbot.chat.entity.Thread
import com.sionicai.chatbot.chat.port.ChatResponsePort
import com.sionicai.chatbot.chat.repository.ChatRepository
import com.sionicai.chatbot.chat.repository.ThreadRepository
import com.sionicai.chatbot.common.client.AiClient
import com.sionicai.chatbot.common.client.ChatMessage
import com.sionicai.chatbot.common.exception.ForbiddenException
import com.sionicai.chatbot.common.exception.ResourceNotFoundException
import com.sionicai.chatbot.user.entity.User
import com.sionicai.chatbot.user.entity.UserRole
import com.sionicai.chatbot.user.repository.UserRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.concurrent.thread

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

        val thread = resolveThreadForUser(userId, user)
        val contextChats = chatRepository.findByThreadIdOrderByCreatedAtAsc(thread.id!!)
        val messages = buildMessages(contextChats, request.question)

        return if (request.isStreaming) {
            val chat = chatRepository.save(
                Chat(
                    question = request.question,
                    answer = "",
                    thread = thread
                )
            )

            chatResponsePort.sendPartialResponse(chat.id!!, "Thinking...")
            startStreamingCompletion(chat.id!!, messages, request.model)

            ChatResponse(
                chatId = chat.id!!,
                threadId = thread.id!!,
                answer = "Thinking...",
                isCompleted = false,
                createdAt = chat.createdAt
            )
        } else {
            val answer = aiClient.chatCompletion(messages, request.model)
            val chat = chatRepository.save(
                Chat(
                    question = request.question,
                    answer = answer,
                    thread = thread
                )
            )

            ChatResponse(
                chatId = chat.id!!,
                threadId = thread.id!!,
                answer = answer,
                isCompleted = true,
                createdAt = chat.createdAt
            )
        }
    }

    @Transactional
    fun getChatStatus(userId: UUID, role: UserRole, chatId: UUID): ChatResponse {
        val chat = chatRepository.findByIdOrNull(chatId)
            ?: throw ResourceNotFoundException("Chat", chatId)

        validateChatAccess(userId, role, chat)

        val inMemoryAnswer = chatResponsePort.getIntermediateResponse(chatId)
        val inMemoryCompleted = chatResponsePort.isCompleted(chatId)

        if (inMemoryCompleted && inMemoryAnswer != null && chat.answer != inMemoryAnswer) {
            chat.answer = inMemoryAnswer
            chatRepository.save(chat)
        }

        val answer = inMemoryAnswer ?: if (chat.answer.isBlank()) "Thinking..." else chat.answer
        val isCompleted = inMemoryCompleted || chat.answer.isNotBlank()

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
        val threads = if (role == UserRole.ADMIN) {
            threadRepository.findAll(pageable)
        } else {
            threadRepository.findAllByUserId(userId, pageable)
        }

        val threadIds = threads.content.mapNotNull { it.id }
        val chatsByThread = if (threadIds.isEmpty()) {
            emptyMap()
        } else {
            chatRepository.findAllByThreadIdInOrderByCreatedAtAsc(threadIds)
                .groupBy { it.thread.id!! }
        }

        return threads.map { thread ->
            val chats = chatsByThread[thread.id!!].orEmpty().map {
                ThreadChatResponse(
                    chatId = it.id!!,
                    question = it.question,
                    answer = it.answer,
                    createdAt = it.createdAt
                )
            }
            ThreadResponse(
                threadId = thread.id!!,
                userId = thread.user.id!!,
                createdAt = thread.createdAt,
                chats = chats
            )
        }
    }

    @Transactional
    fun deleteThread(userId: UUID, threadId: UUID, role: UserRole) {
        val thread = threadRepository.findByIdOrNull(threadId)
            ?: throw ResourceNotFoundException("Thread", threadId)

        if (role != UserRole.ADMIN && thread.user.id != userId) {
            throw ForbiddenException("Access denied")
        }

        chatRepository.deleteAllByThreadId(threadId)
        threadRepository.delete(thread)
    }

    private fun resolveThreadForUser(userId: UUID, user: User): Thread {
        val lastChat = chatRepository.findFirstByThreadUserIdOrderByCreatedAtDesc(userId)
        val now = OffsetDateTime.now()

        return if (lastChat == null || lastChat.createdAt.plusMinutes(30).isBefore(now)) {
            threadRepository.save(Thread(user = user))
        } else {
            lastChat.thread
        }
    }

    private fun buildMessages(contextChats: List<Chat>, question: String): List<ChatMessage> {
        return contextChats.flatMap {
            listOf(
                ChatMessage("user", it.question),
                ChatMessage("assistant", it.answer)
            )
        } + ChatMessage("user", question)
    }

    private fun startStreamingCompletion(chatId: UUID, messages: List<ChatMessage>, model: String?) {
        thread(start = true, name = "chat-stream-$chatId") {
            val answer = try {
                aiClient.chatCompletion(messages, model)
            } catch (e: Exception) {
                "Error: ${e.message ?: "Unknown error"}"
            }

            chatResponsePort.sendCompleteResponse(chatId, answer)
            val chat = chatRepository.findByIdOrNull(chatId) ?: return@thread
            chat.answer = answer
            chatRepository.save(chat)
        }
    }

    private fun validateChatAccess(userId: UUID, role: UserRole, chat: Chat) {
        if (role != UserRole.ADMIN && chat.thread.user.id != userId) {
            throw ForbiddenException("Access denied")
        }
    }
}

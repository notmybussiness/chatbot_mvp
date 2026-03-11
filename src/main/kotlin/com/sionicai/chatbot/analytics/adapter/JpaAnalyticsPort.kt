package com.sionicai.chatbot.analytics.adapter

import com.sionicai.chatbot.analytics.dto.ChatReportDto
import com.sionicai.chatbot.chat.repository.ChatRepository
import com.sionicai.chatbot.user.repository.UserRepository
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

@Component
class JpaAnalyticsPort(
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository
) : AnalyticsPort {

    override fun getSignUpCountSince(since: OffsetDateTime): Long {
        return userRepository.countByCreatedAtAfter(since)
    }

    override fun getChatCountSince(since: OffsetDateTime): Long {
        return chatRepository.countByCreatedAtAfter(since)
    }

    override fun getChatReportDataSince(since: OffsetDateTime): List<ChatReportDto> {
        return chatRepository.findChatReportSince(since)
    }
}

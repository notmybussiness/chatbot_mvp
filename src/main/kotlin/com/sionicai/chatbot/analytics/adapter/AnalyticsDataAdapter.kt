package com.sionicai.chatbot.analytics.adapter

import com.sionicai.chatbot.analytics.dto.ChatReportDto
import com.sionicai.chatbot.analytics.repository.LoginLogRepository
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

@Component
class AnalyticsDataAdapter(
    private val loginLogRepository: LoginLogRepository,
    private val analyticsPort: AnalyticsPort // 추후 UserRepository, ChatRepository로 교체
) {
    fun getSignUpCountSince(since: OffsetDateTime): Long {
        return analyticsPort.getSignUpCountSince(since)
    }

    fun getLoginCountSince(since: OffsetDateTime): Long {
        return loginLogRepository.countByCreatedAtAfter(since)
    }

    fun getChatCountSince(since: OffsetDateTime): Long {
        return analyticsPort.getChatCountSince(since)
    }

    fun getChatReportDataSince(since: OffsetDateTime): List<ChatReportDto> {
        return analyticsPort.getChatReportDataSince(since)
    }
}

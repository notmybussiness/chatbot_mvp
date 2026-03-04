package com.example.chatbot.analytics.adapter

import com.example.chatbot.analytics.dto.ChatReportDto
import java.time.OffsetDateTime

/**
 * 임시 Stub 포트 인터페이스입니다.
 * 타 도메인(User, Chat)의 Repository가 완성되기 전까지
 * Analytics 모듈에서 컴파일 및 테스트가 가능하도록 사용합니다.
 */
interface AnalyticsPort {
    fun getSignUpCountSince(since: OffsetDateTime): Long
    fun getChatCountSince(since: OffsetDateTime): Long
    fun getChatReportDataSince(since: OffsetDateTime): List<ChatReportDto>
}

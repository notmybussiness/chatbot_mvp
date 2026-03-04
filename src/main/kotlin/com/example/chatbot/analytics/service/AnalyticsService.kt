package com.example.chatbot.analytics.service

import com.example.chatbot.analytics.adapter.AnalyticsDataAdapter
import com.example.chatbot.analytics.dto.ActivityResponse
import com.example.chatbot.analytics.dto.ChatReportDto
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

@Service
class AnalyticsService(
    private val analyticsDataAdapter: AnalyticsDataAdapter
) {
    fun getActivity(): ActivityResponse {
        val now = OffsetDateTime.now()
        val since = now.minusHours(24)

        val signUpCount = analyticsDataAdapter.getSignUpCountSince(since)
        val loginCount = analyticsDataAdapter.getLoginCountSince(since)
        val chatCount = analyticsDataAdapter.getChatCountSince(since)

        return ActivityResponse(signUpCount, loginCount, chatCount, since, now)
    }

    fun generateReport(): ByteArray {
        val since = OffsetDateTime.now().minusHours(24)
        val reportData = analyticsDataAdapter.getChatReportDataSince(since)
        
        return buildCsvReport(reportData)
    }

    private fun buildCsvReport(results: List<ChatReportDto>): ByteArray {
        val sb = StringBuilder()
        // CSV 헤더 (BOM 추가로 한글 깨짐 방지)
        sb.append('\uFEFF')
        sb.appendLine("chat_id,question,answer,created_at,user_email,user_name")
        
        results.forEach { row ->
            // 필드 내 따옴표나 쉼표가 있을 수 있으므로 이스케이프 처리
            sb.appendLine("${row.chatId},\"${escapeForCsv(row.question)}\",\"${escapeForCsv(row.answer)}\",${row.createdAt},\"${escapeForCsv(row.userEmail)}\",\"${escapeForCsv(row.userName)}\"")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
    
    private fun escapeForCsv(value: String): String {
        return value.replace("\"", "\"\"")
    }
}

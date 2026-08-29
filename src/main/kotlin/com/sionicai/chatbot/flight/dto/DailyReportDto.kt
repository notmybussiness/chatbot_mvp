package com.sionicai.chatbot.flight.dto

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class DailyFlightReport(
    val reportDate: LocalDate,
    val generatedAt: OffsetDateTime,
    /** 리포트 대상 노선 수 */
    val watchCount: Int,
    /** 오늘 가격을 확보하지 못한 노선 수 (차단·스키마 변경 등) */
    val missingCount: Int,
    val items: List<FlightReportItem>,
    /** AI가 생성한 자연어 요약. AI 호출이 실패해도 리포트 자체는 반환됩니다. */
    val summary: String
)

data class FlightReportItem(
    val watchId: UUID,
    val route: String,
    val departureDate: LocalDate,
    val returnDate: LocalDate?,
    val todayPrice: Long?,
    val previousPrice: Long?,
    /** 직전 수집일 대비 증감액. 양수면 인상, 음수면 인하. */
    val changeAmount: Long?,
    /** 직전 수집일 대비 증감률. -0.12 = 12% 하락 */
    val changeRate: Double?,
    val lowestInWindow: Long?,
    val isLowestInWindow: Boolean,
    val targetPrice: Long?,
    val targetReached: Boolean,
    val airline: String?,
    val stops: Int?,
    val deepLink: String?,
    val source: String?
)

/** 배치 수집 결과 요약 */
data class CollectionResult(
    val collectedOn: LocalDate,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int
)

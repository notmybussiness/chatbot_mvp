package com.sionicai.chatbot.flight.service

import com.sionicai.chatbot.common.client.AiClient
import com.sionicai.chatbot.common.client.ChatMessage
import com.sionicai.chatbot.flight.dto.CollectionResult
import com.sionicai.chatbot.flight.dto.DailyFlightReport
import com.sionicai.chatbot.flight.dto.FlightReportItem
import com.sionicai.chatbot.flight.entity.FlightWatch
import com.sionicai.chatbot.flight.entity.PriceSnapshot
import com.sionicai.chatbot.flight.port.ReportDeliveryPort
import com.sionicai.chatbot.flight.repository.FlightWatchRepository
import com.sionicai.chatbot.flight.repository.PriceSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 누적된 가격 스냅샷을 근거로 데일리 리포트를 만들고 사용자에게 전달합니다.
 *
 * 리포트 작성은 외부 호출을 전혀 하지 않으므로, 과거 날짜 기준으로도 언제든 다시 조회할 수 있습니다.
 */
@Service
class DailyFlightReportService(
    private val flightWatchRepository: FlightWatchRepository,
    private val priceSnapshotRepository: PriceSnapshotRepository,
    private val priceCollectionService: PriceCollectionService,
    private val reportDeliveryPort: ReportDeliveryPort,
    private val aiClient: AiClient,
    @Value("\${flight.report.history-days}") private val historyDays: Long
) {

    private val logger = LoggerFactory.getLogger(DailyFlightReportService::class.java)

    /** 수집 → 사용자별 리포트 생성 → 전달. 스케줄러와 관리자 수동 실행이 공통으로 사용합니다. */
    fun runDailyReport(reportDate: LocalDate = LocalDate.now()): CollectionResult {
        val result = priceCollectionService.collectAll(reportDate)

        flightWatchRepository.findActiveUserIds().forEach { userId ->
            val report = buildReportForUser(userId, reportDate)
            if (report.items.isEmpty()) return@forEach

            reportDeliveryPort.deliver(
                userId = userId,
                title = "✈️ $reportDate 항공권 데일리 리포트",
                body = formatReportBody(report)
            )
        }

        return result
    }

    @Transactional(readOnly = true)
    fun buildReportForUser(userId: UUID, reportDate: LocalDate = LocalDate.now()): DailyFlightReport {
        val watches = flightWatchRepository.findAllByUserIdAndActiveTrue(userId)

        // 노선마다 질의하면 N+1이 되므로 기간 내 스냅샷을 한 번에 가져와 메모리에서 나눕니다.
        val snapshotsByWatch = if (watches.isEmpty()) {
            emptyMap()
        } else {
            priceSnapshotRepository.findAllByWatchIdInAndCollectedOnBetween(
                watches.mapNotNull { it.id },
                reportDate.minusDays(historyDays),
                reportDate
            ).groupBy { it.watchId }
        }

        val items = watches.map { buildItem(it, snapshotsByWatch[it.id].orEmpty(), reportDate) }

        return DailyFlightReport(
            reportDate = reportDate,
            generatedAt = OffsetDateTime.now(),
            watchCount = items.size,
            missingCount = items.count { it.todayPrice == null },
            items = items,
            summary = summarize(items, reportDate)
        )
    }

    private fun buildItem(
        watch: FlightWatch,
        snapshots: List<PriceSnapshot>,
        reportDate: LocalDate
    ): FlightReportItem {
        val byDate = snapshots.groupBy { it.collectedOn }
        val today = byDate[reportDate]?.minByOrNull { it.lowestPrice }
        val previous = byDate.keys.filter { it.isBefore(reportDate) }.maxOrNull()
            ?.let { date -> byDate[date]?.minByOrNull { it.lowestPrice } }
        val lowestInWindow = snapshots.minOfOrNull { it.lowestPrice }

        val changeAmount = if (today != null && previous != null) {
            today.lowestPrice - previous.lowestPrice
        } else null

        val changeRate = if (changeAmount != null && previous != null && previous.lowestPrice > 0) {
            changeAmount.toDouble() / previous.lowestPrice
        } else null

        return FlightReportItem(
            watchId = watch.id!!,
            route = watch.route,
            departureDate = watch.departureDate,
            returnDate = watch.returnDate,
            todayPrice = today?.lowestPrice,
            previousPrice = previous?.lowestPrice,
            changeAmount = changeAmount,
            changeRate = changeRate,
            lowestInWindow = lowestInWindow,
            isLowestInWindow = today != null && lowestInWindow != null && today.lowestPrice <= lowestInWindow,
            targetPrice = watch.targetPrice,
            targetReached = watch.targetPrice?.let { target -> today != null && today.lowestPrice <= target } ?: false,
            airline = today?.airline,
            stops = today?.stops,
            deepLink = today?.deepLink,
            source = today?.source
        )
    }

    /**
     * 수집 결과를 자연어로 요약합니다.
     * AI 호출이 실패하더라도 리포트는 나가야 하므로, 실패 시 사실 요약본을 그대로 사용합니다.
     */
    private fun summarize(items: List<FlightReportItem>, reportDate: LocalDate): String {
        if (items.isEmpty()) {
            return "추적 중인 노선이 없습니다. POST /api/flights/watches 로 노선을 등록해 주세요."
        }

        val factSheet = buildFactSheet(items, reportDate)

        return try {
            aiClient.chatCompletion(
                listOf(
                    ChatMessage("system", SUMMARY_SYSTEM_PROMPT),
                    ChatMessage("user", factSheet)
                )
            )
        } catch (e: Exception) {
            logger.warn("[FlightReport] AI 요약 실패, 사실 요약본으로 대체합니다: {}", e.message)
            factSheet
        }
    }

    private fun buildFactSheet(items: List<FlightReportItem>, reportDate: LocalDate): String = buildString {
        appendLine("기준일: $reportDate")
        appendLine("추적 노선 수: ${items.size}")
        appendLine()
        items.forEach { item ->
            append("- ${item.route} (출발 ${item.departureDate}")
            item.returnDate?.let { append(" / 귀국 $it") }
            appendLine(")")

            if (item.todayPrice == null) {
                appendLine("  오늘 가격: 수집 실패")
            } else {
                appendLine("  오늘 최저가: ${money(item.todayPrice)} (${item.airline}, ${stopsLabel(item.stops)})")
                appendLine("  직전 대비: ${changeLabel(item.previousPrice, item.changeAmount, item.changeRate)}")
                item.lowestInWindow?.let {
                    val mark = if (item.isLowestInWindow) " (오늘이 최저)" else ""
                    appendLine("  최근 ${historyDays}일 최저가: ${money(it)}$mark")
                }
                item.targetPrice?.let {
                    appendLine("  목표가: ${money(it)}${if (item.targetReached) " → 도달" else " → 미도달"}")
                }
            }
        }
    }

    private fun formatReportBody(report: DailyFlightReport): String = buildString {
        appendLine(report.summary.trim())
        appendLine()
        appendLine("─".repeat(30))
        appendLine(buildFactSheet(report.items, report.reportDate).trim())
        if (report.missingCount > 0) {
            appendLine()
            appendLine("※ ${report.missingCount}개 노선은 오늘 가격을 가져오지 못했습니다. 서버 로그를 확인해 주세요.")
        }
        report.items.firstOrNull { it.deepLink != null }?.let {
            appendLine()
            appendLine("예매 링크 예시: ${it.deepLink}")
        }
    }

    private fun changeLabel(previous: Long?, amount: Long?, rate: Double?): String {
        if (previous == null || amount == null) return "비교할 이전 데이터 없음"
        if (amount == 0L) return "변동 없음"

        val direction = if (amount > 0) "인상" else "인하"
        val percent = rate?.let { " (${abs(it * 100).roundToInt()}%)" } ?: ""
        return "${money(abs(amount))} $direction$percent, 이전 ${money(previous)}"
    }

    private fun stopsLabel(stops: Int?): String = when (stops) {
        null -> "경유 정보 없음"
        0 -> "직항"
        else -> "경유 ${stops}회"
    }

    private fun money(value: Long): String =
        "${NumberFormat.getNumberInstance(Locale.KOREA).format(value)}원"

    companion object {
        private const val SUMMARY_SYSTEM_PROMPT =
            "당신은 항공권 가격 분석 어시스턴트입니다. 아래 수집 데이터만 근거로 한국어 3~5문장 요약을 작성하세요. " +
                "데이터에 없는 가격·항공사·노선을 추측하거나 지어내지 마세요. " +
                "가격이 크게 내린 노선과 목표가에 도달한 노선을 먼저 언급하고, 지금 사는 게 유리한지 한 문장으로 조언하세요."
    }
}

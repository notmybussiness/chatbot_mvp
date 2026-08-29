package com.sionicai.chatbot.flight.scheduler

import com.sionicai.chatbot.flight.service.DailyFlightReportService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 스케줄링은 데일리 리포트를 명시적으로 켰을 때만 활성화합니다.
 * 기본값을 꺼짐으로 둔 덕분에 테스트나 로컬 실행 중 외부 조회가 예기치 않게 돌지 않습니다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["flight.report.enabled"], havingValue = "true")
class FlightSchedulingConfig

@Component
@ConditionalOnProperty(name = ["flight.report.enabled"], havingValue = "true")
class DailyFlightReportScheduler(
    private val dailyFlightReportService: DailyFlightReportService
) {

    private val logger = LoggerFactory.getLogger(DailyFlightReportScheduler::class.java)

    @Scheduled(cron = "\${flight.report.cron}", zone = "\${flight.report.zone}")
    fun runDailyReport() {
        logger.info("[FlightReport] 스케줄 실행 시작")
        try {
            val result = dailyFlightReportService.runDailyReport()
            logger.info(
                "[FlightReport] 스케줄 실행 완료: 성공 {}건, 실패 {}건, 건너뜀 {}건",
                result.succeeded,
                result.failed,
                result.skipped
            )
        } catch (e: Exception) {
            // 예외가 스케줄러 밖으로 나가면 다음 실행이 막힐 수 있어 여기서 종료시킵니다.
            logger.error("[FlightReport] 스케줄 실행 중 오류", e)
        }
    }
}

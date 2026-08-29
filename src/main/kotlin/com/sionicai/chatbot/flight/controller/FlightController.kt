package com.sionicai.chatbot.flight.controller

import com.sionicai.chatbot.common.dto.ApiResponse
import com.sionicai.chatbot.common.dto.PageResponse
import com.sionicai.chatbot.flight.client.CabinClass
import com.sionicai.chatbot.flight.client.FlightSearchClient
import com.sionicai.chatbot.flight.client.FlightSearchQuery
import com.sionicai.chatbot.flight.client.SearchDiagnostics
import com.sionicai.chatbot.flight.dto.CollectionResult
import com.sionicai.chatbot.flight.dto.CreateFlightWatchRequest
import com.sionicai.chatbot.flight.dto.DailyFlightReport
import com.sionicai.chatbot.flight.dto.FlightWatchResponse
import com.sionicai.chatbot.flight.dto.UpdateFlightWatchRequest
import com.sionicai.chatbot.flight.service.DailyFlightReportService
import com.sionicai.chatbot.flight.service.FlightWatchService
import com.sionicai.chatbot.user.entity.UserRole
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/flights")
class FlightController(
    private val flightWatchService: FlightWatchService,
    private val dailyFlightReportService: DailyFlightReportService,
    private val flightSearchClient: FlightSearchClient
) {

    private fun extractUserRole(authentication: Authentication): UserRole {
        val authority = authentication.authorities.firstOrNull()?.authority ?: "ROLE_MEMBER"
        return if (authority == "ROLE_ADMIN") UserRole.ADMIN else UserRole.MEMBER
    }

    // ---- 추적 노선(watch) ----

    @PostMapping("/watches")
    fun createWatch(
        @AuthenticationPrincipal userId: UUID,
        @Valid @RequestBody request: CreateFlightWatchRequest
    ): ResponseEntity<ApiResponse<FlightWatchResponse>> {
        val response = flightWatchService.create(userId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response))
    }

    @GetMapping("/watches")
    fun listWatches(
        @AuthenticationPrincipal userId: UUID,
        authentication: Authentication,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "desc") sort: String
    ): ResponseEntity<ApiResponse<PageResponse<FlightWatchResponse>>> {
        val role = extractUserRole(authentication)
        return ResponseEntity.ok(ApiResponse.success(flightWatchService.list(userId, role, page, size, sort)))
    }

    @PatchMapping("/watches/{watchId}")
    fun updateWatch(
        @AuthenticationPrincipal userId: UUID,
        authentication: Authentication,
        @PathVariable watchId: UUID,
        @Valid @RequestBody request: UpdateFlightWatchRequest
    ): ResponseEntity<ApiResponse<FlightWatchResponse>> {
        val role = extractUserRole(authentication)
        return ResponseEntity.ok(ApiResponse.success(flightWatchService.update(userId, role, watchId, request)))
    }

    @DeleteMapping("/watches/{watchId}")
    fun deleteWatch(
        @AuthenticationPrincipal userId: UUID,
        authentication: Authentication,
        @PathVariable watchId: UUID
    ): ResponseEntity<ApiResponse<Unit?>> {
        val role = extractUserRole(authentication)
        flightWatchService.delete(userId, role, watchId)
        return ResponseEntity.ok(ApiResponse(success = true))
    }

    // ---- 데일리 리포트 ----

    /** 이미 수집된 스냅샷만으로 리포트를 만듭니다. 외부 조회를 새로 하지 않습니다. */
    @GetMapping("/report/daily")
    fun getDailyReport(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?
    ): ResponseEntity<ApiResponse<DailyFlightReport>> {
        val report = dailyFlightReportService.buildReportForUser(userId, date ?: LocalDate.now())
        return ResponseEntity.ok(ApiResponse.success(report))
    }

    /**
     * 스케줄을 기다리지 않고 수집 + 리포트 전달을 즉시 실행합니다.
     * 외부 조회를 유발하므로 관리자만 호출할 수 있습니다.
     */
    @PostMapping("/report/run")
    @PreAuthorize("hasRole('ADMIN')")
    fun runReportNow(): ResponseEntity<ApiResponse<CollectionResult>> {
        return ResponseEntity.ok(ApiResponse.success(dailyFlightReportService.runDailyReport()))
    }

    // ---- 연동 점검 ----

    /**
     * 현재 provider로 한 번 조회해, 실제로 보낸 요청과 받은 응답을 그대로 돌려줍니다.
     *
     * 네이버처럼 스키마가 문서화되지 않은 소스는 "왜 가격이 안 잡히는지"를 눈으로 봐야 고칠 수 있습니다.
     * 원본 응답이 노출되고 외부 호출을 유발하므로 관리자 전용입니다.
     */
    @PostMapping("/diagnostics")
    @PreAuthorize("hasRole('ADMIN')")
    fun diagnose(
        @RequestParam origin: String,
        @RequestParam destination: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) departureDate: LocalDate,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) returnDate: LocalDate?,
        @RequestParam(defaultValue = "1") adults: Int,
        @RequestParam(defaultValue = "ECONOMY") cabin: CabinClass
    ): ResponseEntity<ApiResponse<SearchDiagnostics>> {
        val diagnostics = flightSearchClient.diagnose(
            FlightSearchQuery(
                origin = origin.uppercase(),
                destination = destination.uppercase(),
                departureDate = departureDate,
                returnDate = returnDate,
                adults = adults,
                cabin = cabin
            )
        )
        return ResponseEntity.ok(ApiResponse.success(diagnostics))
    }
}

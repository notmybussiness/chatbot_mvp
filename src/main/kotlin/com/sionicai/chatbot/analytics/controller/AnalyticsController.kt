package com.sionicai.chatbot.analytics.controller

import com.sionicai.chatbot.analytics.dto.ActivityResponse
import com.sionicai.chatbot.analytics.service.AnalyticsService
import com.sionicai.chatbot.common.dto.ApiResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.RequestMapping
import org.springframework.web.bind.RestController
import org.springframework.web.bind.annotation.GetMapping
import java.time.LocalDate

@RestController
@RequestMapping("/api/analytics")
class AnalyticsController(private val analyticsService: AnalyticsService) {

    @GetMapping("/activity")
    @PreAuthorize("hasRole('ADMIN')")
    fun getActivity(): ResponseEntity<ApiResponse<ActivityResponse>> {
        return ResponseEntity.ok(ApiResponse.success(analyticsService.getActivity()))
    }

    @GetMapping("/report")
    @PreAuthorize("hasRole('ADMIN')")
    fun generateReport(): ResponseEntity<ByteArray> {
        val csv = analyticsService.generateReport()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report_${LocalDate.now()}.csv\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv)
    }
}

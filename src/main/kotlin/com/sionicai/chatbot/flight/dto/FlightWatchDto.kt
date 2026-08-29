package com.sionicai.chatbot.flight.dto

import com.sionicai.chatbot.flight.client.CabinClass
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

private const val IATA_PATTERN = "^[A-Za-z]{3}$"

data class CreateFlightWatchRequest(
    @field:NotBlank
    @field:Pattern(regexp = IATA_PATTERN, message = "출발지는 3자리 IATA 공항코드여야 합니다 (예: ICN)")
    val origin: String,

    @field:NotBlank
    @field:Pattern(regexp = IATA_PATTERN, message = "도착지는 3자리 IATA 공항코드여야 합니다 (예: NRT)")
    val destination: String,

    @field:NotNull
    @field:Future(message = "출발일은 미래 날짜여야 합니다")
    val departureDate: LocalDate,

    val returnDate: LocalDate? = null,

    @field:Min(1) @field:Max(9)
    val adults: Int = 1,

    val cabin: CabinClass = CabinClass.ECONOMY,

    @field:Positive(message = "목표가는 0보다 커야 합니다")
    val targetPrice: Long? = null
)

data class UpdateFlightWatchRequest(
    @field:Positive(message = "목표가는 0보다 커야 합니다")
    val targetPrice: Long? = null,
    val active: Boolean? = null
)

data class FlightWatchResponse(
    val id: UUID,
    val userId: UUID,
    val origin: String,
    val destination: String,
    val departureDate: LocalDate,
    val returnDate: LocalDate?,
    val adults: Int,
    val cabin: CabinClass,
    val targetPrice: Long?,
    val active: Boolean,
    val createdAt: OffsetDateTime
)

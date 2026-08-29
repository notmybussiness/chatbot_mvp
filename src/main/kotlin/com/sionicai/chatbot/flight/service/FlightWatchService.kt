package com.sionicai.chatbot.flight.service

import com.sionicai.chatbot.common.dto.PageResponse
import com.sionicai.chatbot.common.exception.BusinessException
import com.sionicai.chatbot.common.exception.ForbiddenException
import com.sionicai.chatbot.common.exception.ResourceNotFoundException
import com.sionicai.chatbot.flight.dto.CreateFlightWatchRequest
import com.sionicai.chatbot.flight.dto.FlightWatchResponse
import com.sionicai.chatbot.flight.dto.UpdateFlightWatchRequest
import com.sionicai.chatbot.flight.entity.FlightWatch
import com.sionicai.chatbot.flight.repository.FlightWatchRepository
import com.sionicai.chatbot.flight.repository.PriceSnapshotRepository
import com.sionicai.chatbot.user.entity.UserRole
import com.sionicai.chatbot.user.repository.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class FlightWatchService(
    private val flightWatchRepository: FlightWatchRepository,
    private val priceSnapshotRepository: PriceSnapshotRepository,
    private val userRepository: UserRepository
) {

    @Transactional
    fun create(userId: UUID, request: CreateFlightWatchRequest): FlightWatchResponse {
        val user = userRepository.findByIdOrNull(userId)
            ?: throw ResourceNotFoundException("User", userId)

        val origin = request.origin.uppercase()
        val destination = request.destination.uppercase()

        if (origin == destination) {
            throw BusinessException("INVALID_ROUTE", "출발지와 도착지가 같을 수 없습니다")
        }
        if (request.returnDate != null && request.returnDate.isBefore(request.departureDate)) {
            throw BusinessException("INVALID_DATE_RANGE", "귀국일은 출발일보다 빠를 수 없습니다")
        }

        return flightWatchRepository.save(
            FlightWatch(
                user = user,
                origin = origin,
                destination = destination,
                departureDate = request.departureDate,
                returnDate = request.returnDate,
                adults = request.adults,
                cabin = request.cabin,
                targetPrice = request.targetPrice
            )
        ).toResponse()
    }

    @Transactional(readOnly = true)
    fun list(userId: UUID, role: UserRole, page: Int, size: Int, sort: String): PageResponse<FlightWatchResponse> {
        val direction = if (sort.equals("asc", ignoreCase = true)) Sort.Direction.ASC else Sort.Direction.DESC
        val pageable = PageRequest.of(page, size, Sort.by(direction, "createdAt"))

        val result = if (role == UserRole.ADMIN) {
            flightWatchRepository.findAll(pageable)
        } else {
            flightWatchRepository.findAllByUserId(userId, pageable)
        }

        return PageResponse.of(
            content = result.content.map { it.toResponse() },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements
        )
    }

    @Transactional
    fun update(userId: UUID, role: UserRole, watchId: UUID, request: UpdateFlightWatchRequest): FlightWatchResponse {
        val watch = findAccessibleWatch(userId, role, watchId)

        request.targetPrice?.let { watch.targetPrice = it }
        request.active?.let { watch.active = it }

        return flightWatchRepository.save(watch).toResponse()
    }

    @Transactional
    fun delete(userId: UUID, role: UserRole, watchId: UUID) {
        val watch = findAccessibleWatch(userId, role, watchId)

        // 스냅샷은 watchId만 들고 있는 별도 테이블이라 함께 정리해야 고아 데이터가 남지 않습니다.
        priceSnapshotRepository.deleteAllByWatchId(watchId)
        flightWatchRepository.delete(watch)
    }

    private fun findAccessibleWatch(userId: UUID, role: UserRole, watchId: UUID): FlightWatch {
        val watch = flightWatchRepository.findByIdOrNull(watchId)
            ?: throw ResourceNotFoundException("FlightWatch", watchId)

        if (role != UserRole.ADMIN && watch.user.id != userId) {
            throw ForbiddenException("Access denied")
        }
        return watch
    }
}

fun FlightWatch.toResponse(): FlightWatchResponse = FlightWatchResponse(
    id = id!!,
    userId = user.id!!,
    origin = origin,
    destination = destination,
    departureDate = departureDate,
    returnDate = returnDate,
    adults = adults,
    cabin = cabin,
    targetPrice = targetPrice,
    active = active,
    createdAt = createdAt
)

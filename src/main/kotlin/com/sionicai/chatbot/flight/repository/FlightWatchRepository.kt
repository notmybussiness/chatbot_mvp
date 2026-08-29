package com.sionicai.chatbot.flight.repository

import com.sionicai.chatbot.flight.entity.FlightWatch
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface FlightWatchRepository : JpaRepository<FlightWatch, UUID> {

    fun findAllByUserId(userId: UUID, pageable: Pageable): Page<FlightWatch>

    fun findAllByUserIdAndActiveTrue(userId: UUID): List<FlightWatch>

    fun findAllByActiveTrue(): List<FlightWatch>

    /**
     * 리포트를 받아야 할 사용자 ID 목록.
     * 엔티티를 로딩한 뒤 LAZY 연관을 타지 않도록 ID만 직접 조회합니다.
     */
    @Query("select distinct w.user.id from FlightWatch w where w.active = true")
    fun findActiveUserIds(): List<UUID>
}

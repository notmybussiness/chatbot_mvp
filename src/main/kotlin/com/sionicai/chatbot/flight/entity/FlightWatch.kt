package com.sionicai.chatbot.flight.entity

import com.sionicai.chatbot.common.entity.BaseEntity
import com.sionicai.chatbot.flight.client.CabinClass
import com.sionicai.chatbot.user.entity.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDate

/**
 * 사용자가 등록한 가격 추적 대상 노선.
 * 데일리 배치는 활성(active) 상태인 항목만 순회하며 최저가를 수집합니다.
 */
@Entity
@Table(name = "flight_watches")
class FlightWatch(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false, length = 3)
    val origin: String,

    @Column(nullable = false, length = 3)
    val destination: String,

    @Column(name = "departure_date", nullable = false)
    val departureDate: LocalDate,

    @Column(name = "return_date")
    val returnDate: LocalDate? = null,

    @Column(nullable = false)
    val adults: Int = 1,

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    val cabin: CabinClass = CabinClass.ECONOMY,

    /** 이 가격 이하로 떨어지면 리포트에서 "목표가 도달"로 강조합니다. null이면 추적만 합니다. */
    @Column(name = "target_price")
    var targetPrice: Long? = null,

    @Column(nullable = false)
    var active: Boolean = true
) : BaseEntity() {

    val route: String get() = "$origin → $destination"
}

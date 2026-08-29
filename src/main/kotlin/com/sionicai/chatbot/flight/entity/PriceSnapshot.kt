package com.sionicai.chatbot.flight.entity

import com.sionicai.chatbot.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDate
import java.util.UUID

/**
 * 특정 날짜에 수집한 노선별 최저가 기록.
 *
 * 단발 조회만으로는 싼지 비싼지 판단할 수 없기 때문에, 이 스냅샷을 날마다 쌓아
 * 전일 대비 등락과 최근 최저가를 계산합니다. 데일리 리포트의 실질적인 근거 데이터입니다.
 */
@Entity
@Table(
    name = "price_snapshots",
    indexes = [Index(name = "idx_snapshot_watch_collected", columnList = "watch_id, collected_on")]
)
class PriceSnapshot(
    @Column(name = "watch_id", nullable = false)
    val watchId: UUID,

    @Column(name = "collected_on", nullable = false)
    val collectedOn: LocalDate,

    @Column(name = "lowest_price", nullable = false)
    val lowestPrice: Long,

    @Column(nullable = false, length = 8)
    val currency: String,

    @Column(nullable = false)
    val airline: String,

    @Column(nullable = false)
    val stops: Int,

    /** 그날 관측된 항공권 개수. 1개만 잡혔다면 파싱이 부분적으로 실패했을 수 있다는 신호입니다. */
    @Column(name = "offer_count", nullable = false)
    val offerCount: Int,

    @Column(name = "deep_link", columnDefinition = "TEXT")
    val deepLink: String? = null,

    /** 수집 출처 (`mock`, `naver` 등). provider를 바꿔가며 수집한 이력을 구분합니다. */
    @Column(nullable = false, length = 32)
    val source: String
) : BaseEntity()

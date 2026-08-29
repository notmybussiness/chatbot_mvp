package com.sionicai.chatbot.flight.repository

import com.sionicai.chatbot.flight.entity.PriceSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.UUID

interface PriceSnapshotRepository : JpaRepository<PriceSnapshot, UUID> {

    /**
     * 여러 노선의 기간 내 스냅샷을 한 번에 가져옵니다.
     * 리포트를 만들 때 노선마다 질의하면 N+1이 되므로 일괄 조회합니다.
     */
    fun findAllByWatchIdInAndCollectedOnBetween(
        watchIds: Collection<UUID>,
        from: LocalDate,
        to: LocalDate
    ): List<PriceSnapshot>

    /** 해당 날짜에 이미 수집이 끝난 노선 ID. 같은 날 재실행 시 중복 수집을 건너뛰는 데 씁니다. */
    @Query("select distinct s.watchId from PriceSnapshot s where s.collectedOn = :collectedOn")
    fun findWatchIdsCollectedOn(@Param("collectedOn") collectedOn: LocalDate): List<UUID>

    fun deleteAllByWatchId(watchId: UUID)
}

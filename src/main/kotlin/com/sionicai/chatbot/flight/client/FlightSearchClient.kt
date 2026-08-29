package com.sionicai.chatbot.flight.client

import java.time.LocalDate

/**
 * 항공권 최저가 조회를 위한 전략 인터페이스.
 *
 * [AiClient][com.sionicai.chatbot.common.client.AiClient]와 동일한 전략 패턴을 따르며,
 * `flight.provider` 설정값으로 구현체가 선택됩니다.
 * 소스가 막히거나 바뀌면 구현체를 추가하고 설정만 교체하면 됩니다.
 */
interface FlightSearchClient {

    /**
     * 검색 조건에 해당하는 항공권을 가격 오름차순으로 반환합니다.
     * 조회에 실패하면 [FlightSearchException]을 던집니다. 빈 목록을 반환하지 않습니다.
     */
    fun search(query: FlightSearchQuery): List<FlightOffer>

    /** 스냅샷에 기록될 데이터 출처 이름 (예: `mock`, `naver`) */
    val providerName: String

    /**
     * 연동 상태 점검용 조회.
     *
     * 비공식 API를 쓰는 구현체는 응답 스키마가 예고 없이 바뀔 수 있어서,
     * "왜 가격을 못 찾았는지"를 눈으로 확인할 수단이 필요합니다.
     * 기본 구현은 검색 결과만 담아 돌려줍니다.
     */
    fun diagnose(query: FlightSearchQuery): SearchDiagnostics =
        try {
            SearchDiagnostics(provider = providerName, offers = search(query))
        } catch (e: Exception) {
            SearchDiagnostics(provider = providerName, error = e.message)
        }
}

data class FlightSearchQuery(
    val origin: String,
    val destination: String,
    val departureDate: LocalDate,
    val returnDate: LocalDate? = null,
    val adults: Int = 1,
    val cabin: CabinClass = CabinClass.ECONOMY
) {
    val isRoundTrip: Boolean get() = returnDate != null
}

data class FlightOffer(
    val price: Long,
    val currency: String = "KRW",
    val airline: String,
    val stops: Int,
    val deepLink: String? = null
)

enum class CabinClass(val naverCode: String) {
    ECONOMY("Y"),
    BUSINESS("C"),
    FIRST("F")
}

/**
 * 연동 점검 결과. 실제로 무엇을 보냈고 무엇을 돌려받았는지 그대로 노출해
 * 스키마가 어긋났을 때 추측 없이 원인을 짚을 수 있게 합니다.
 */
data class SearchDiagnostics(
    val provider: String,
    /** 실제로 전송한 요청 본문 */
    val requestBody: String? = null,
    /** 원본 응답 앞부분 (전체를 담으면 응답이 지나치게 커집니다) */
    val rawResponseHead: String? = null,
    val responseBytes: Int? = null,
    /** 응답에서 실제로 가격으로 인식된 필드명들. 비어 있으면 후보 키 목록을 넓혀야 합니다. */
    val matchedPriceKeys: List<String> = emptyList(),
    val offers: List<FlightOffer> = emptyList(),
    val error: String? = null
)

class FlightSearchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

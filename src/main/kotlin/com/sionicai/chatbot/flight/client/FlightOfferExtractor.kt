package com.sionicai.chatbot.flight.client

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 응답 JSON에서 항공권 가격을 뽑아내는 추출기.
 *
 * 네이버 항공권처럼 스키마가 문서화되지 않은 소스를 다루기 때문에, 특정 경로를 고정하지 않고
 * JSON 트리 전체를 훑으면서 "가격처럼 보이는 숫자 필드를 가진 객체"를 후보로 수집합니다.
 * 필드가 어디로 옮겨가더라도 이름만 유지되면 계속 동작합니다.
 *
 * 항공사·통화·링크는 가격과 같은 객체에 없을 때가 많아(운임 객체가 여정 객체 아래 중첩되는 형태),
 * 상위 노드에서 본 값을 물려받아 채웁니다.
 *
 * 후보 키 목록은 설정으로 넓힐 수 있어, 스키마가 바뀌어도 재컴파일 없이 대응할 수 있습니다.
 */
@Component
class FlightOfferExtractor(
    @Value("\${flight.extractor.price-keys}") priceKeys: String,
    @Value("\${flight.extractor.airline-keys}") airlineKeys: String,
    @Value("\${flight.extractor.stop-keys}") stopKeys: String,
    @Value("\${flight.extractor.currency-keys}") currencyKeys: String,
    @Value("\${flight.extractor.link-keys}") linkKeys: String,
    /** 이 금액 미만은 유류할증료·세금 등으로 보고 무시합니다. */
    @Value("\${flight.extractor.min-price}") private val minPrice: Long
) {
    private val priceKeys: List<String> = priceKeys.toKeyList()
    private val airlineKeys: List<String> = airlineKeys.toKeyList()
    private val stopKeys: List<String> = stopKeys.toKeyList()
    private val currencyKeys: List<String> = currencyKeys.toKeyList()
    private val linkKeys: List<String> = linkKeys.toKeyList()

    fun extract(root: JsonNode): ExtractionResult {
        val offers = mutableListOf<FlightOffer>()
        val matchedKeys = linkedSetOf<String>()

        walk(root, Inherited(), offers, matchedKeys)

        return ExtractionResult(
            // 같은 운임이 상위·하위 노드에 중복 등장할 수 있어 정리하고, 가격순으로 정렬합니다.
            offers = offers.distinctBy { it.price to it.airline }.sortedBy { it.price },
            matchedPriceKeys = matchedKeys.toList()
        )
    }

    private fun walk(
        node: JsonNode,
        inherited: Inherited,
        offers: MutableList<FlightOffer>,
        matchedKeys: MutableSet<String>
    ) {
        when {
            node.isArray -> node.forEach { walk(it, inherited, offers, matchedKeys) }

            node.isObject -> {
                val context = Inherited(
                    airline = textOf(node, airlineKeys) ?: inherited.airline,
                    currency = textOf(node, currencyKeys) ?: inherited.currency,
                    link = textOf(node, linkKeys) ?: inherited.link,
                    stops = intOf(node, stopKeys) ?: inherited.stops
                )

                priceOf(node)?.let { (key, price) ->
                    matchedKeys += key
                    offers += FlightOffer(
                        price = price,
                        currency = context.currency ?: "KRW",
                        airline = context.airline ?: UNKNOWN_AIRLINE,
                        stops = context.stops ?: 0,
                        deepLink = context.link
                    )
                }

                node.fields().forEach { walk(it.value, context, offers, matchedKeys) }
            }
        }
    }

    private fun priceOf(node: JsonNode): Pair<String, Long>? {
        for (key in priceKeys) {
            val value = node.get(key) ?: continue
            val price = when {
                value.isNumber -> value.asLong()
                value.isTextual -> value.asText().replace(NON_DIGITS, "").toLongOrNull()
                else -> null
            } ?: continue
            if (price >= minPrice) return key to price
        }
        return null
    }

    private fun textOf(node: JsonNode, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key ->
            node.get(key)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
        }

    private fun intOf(node: JsonNode, keys: List<String>): Int? =
        keys.firstNotNullOfOrNull { key -> node.get(key)?.takeIf { it.isNumber }?.asInt() }

    private data class Inherited(
        val airline: String? = null,
        val currency: String? = null,
        val link: String? = null,
        val stops: Int? = null
    )

    companion object {
        const val UNKNOWN_AIRLINE = "미상"
        private val NON_DIGITS = Regex("[^0-9]")

        private fun String.toKeyList(): List<String> =
            split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}

data class ExtractionResult(
    val offers: List<FlightOffer>,
    /** 응답에서 실제로 가격으로 인식된 필드명. 비어 있으면 후보 키를 넓혀야 한다는 신호입니다. */
    val matchedPriceKeys: List<String>
)

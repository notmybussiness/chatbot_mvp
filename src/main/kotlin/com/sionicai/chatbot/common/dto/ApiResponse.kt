package com.sionicai.chatbot.common.dto

import java.time.OffsetDateTime

data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val timestamp: OffsetDateTime = OffsetDateTime.now()
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> =
            ApiResponse(success = true, data = data)

        fun error(message: String): ApiResponse<Nothing> =
            ApiResponse(success = false, error = message)
    }
}

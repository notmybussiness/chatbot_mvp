package com.sionicai.chatbot.common.dto

data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
) {
    companion object {
        fun <T> of(
            content: List<T>,
            page: Int,
            size: Int,
            totalElements: Long
        ): PageResponse<T> = PageResponse(
            content = content,
            page = page,
            size = size,
            totalElements = totalElements,
            totalPages = if (size > 0) ((totalElements + size - 1) / size).toInt() else 0
        )
    }
}

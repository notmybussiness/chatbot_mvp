package com.sionicai.chatbot.analytics.dto

import java.time.OffsetDateTime

data class ActivityResponse(
    val signUpCount: Long,
    val loginCount: Long,
    val chatCount: Long,
    val periodStart: OffsetDateTime,
    val periodEnd: OffsetDateTime
)

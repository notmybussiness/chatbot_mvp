package com.sionicai.chatbot.common.security

import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * SecurityContext에서 현재 인증된 사용자 정보를 추출하는 유틸리티.
 * Controller에서 편하게 사용하기 위한 헬퍼.
 */
object SecurityUtils {

    fun getCurrentUserId(): UUID {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: throw IllegalStateException("No authentication in SecurityContext")
        return authentication.principal as UUID
    }

    fun getCurrentUserRole(): String {
        val authentication = SecurityContextHolder.getContext().authentication
            ?: throw IllegalStateException("No authentication in SecurityContext")
        return authentication.authorities
            .firstOrNull()?.authority?.removePrefix("ROLE_")?.lowercase()
            ?: "member"
    }

    fun isAdmin(): Boolean {
        return getCurrentUserRole() == "admin"
    }
}

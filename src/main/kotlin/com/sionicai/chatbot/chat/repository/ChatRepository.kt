package com.sionicai.chatbot.chat.repository

import com.sionicai.chatbot.analytics.dto.ChatReportDto
import com.sionicai.chatbot.chat.entity.Chat
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface ChatRepository : JpaRepository<Chat, UUID> {
    fun findFirstByThreadUserIdOrderByCreatedAtDesc(userId: UUID): Chat?
    fun findByThreadIdOrderByCreatedAtAsc(threadId: UUID): List<Chat>
    fun findAllByThreadIdInOrderByCreatedAtAsc(threadIds: Collection<UUID>): List<Chat>
    fun deleteAllByThreadId(threadId: UUID)
    fun countByCreatedAtAfter(after: OffsetDateTime): Long

    @Query(
        """
        select new com.sionicai.chatbot.analytics.dto.ChatReportDto(
            c.id, c.question, c.answer, c.createdAt, u.email, u.name
        )
        from Chat c
        join c.thread t
        join t.user u
        where c.createdAt >= :since
        order by c.createdAt desc
        """
    )
    fun findChatReportSince(@Param("since") since: OffsetDateTime): List<ChatReportDto>
}

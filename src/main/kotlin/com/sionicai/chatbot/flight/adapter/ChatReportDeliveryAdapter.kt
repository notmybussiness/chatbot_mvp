package com.sionicai.chatbot.flight.adapter

import com.sionicai.chatbot.chat.entity.Chat
import com.sionicai.chatbot.chat.entity.Thread
import com.sionicai.chatbot.chat.repository.ChatRepository
import com.sionicai.chatbot.chat.repository.ThreadRepository
import com.sionicai.chatbot.flight.port.ReportDeliveryPort
import com.sionicai.chatbot.user.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 리포트를 챗봇 대화로 적재하는 어댑터.
 *
 * 리포트마다 새 스레드를 만듭니다. 스레드의 30분 재사용 규칙은 사용자가 직접 던진 질문의 문맥을
 * 잇기 위한 것이므로, 시스템이 만든 리포트가 진행 중인 대화에 끼어들면 안 됩니다.
 */
@Component
class ChatReportDeliveryAdapter(
    private val userRepository: UserRepository,
    private val threadRepository: ThreadRepository,
    private val chatRepository: ChatRepository
) : ReportDeliveryPort {

    private val logger = LoggerFactory.getLogger(ChatReportDeliveryAdapter::class.java)

    @Transactional
    override fun deliver(userId: UUID, title: String, body: String) {
        val user = userRepository.findByIdOrNull(userId)
        if (user == null) {
            logger.warn("[FlightReport] 사용자를 찾을 수 없어 리포트 전달을 건너뜁니다: {}", userId)
            return
        }

        val thread = threadRepository.save(Thread(user = user))
        chatRepository.save(Chat(question = title, answer = body, thread = thread))

        logger.info("[FlightReport] 사용자 {} 에게 리포트 전달 완료 (thread={})", userId, thread.id)
    }
}

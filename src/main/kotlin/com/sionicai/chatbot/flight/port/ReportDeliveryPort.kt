package com.sionicai.chatbot.flight.port

import java.util.UUID

/**
 * 완성된 데일리 리포트를 사용자에게 전달하는 통로.
 *
 * 현재 구현체는 챗봇 스레드에 대화로 적재해, 사용자가 기존 채팅 UI에서 그대로 읽게 합니다.
 * 이메일·슬랙 등 다른 채널이 필요해지면 이 인터페이스의 구현체만 추가하면 됩니다.
 */
interface ReportDeliveryPort {
    fun deliver(userId: UUID, title: String, body: String)
}

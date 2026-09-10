"""Telegram Bot 알림. 토큰/챗ID 는 .env 에서만 온다.

봇 만들기: Telegram 에서 @BotFather 에게 /newbot → 토큰 발급 → 봇에게 아무 말이나 보낸 뒤
https://api.telegram.org/bot<토큰>/getUpdates 에서 chat.id 확인.
"""

from __future__ import annotations

import time

import requests

from .base import Notifier, NotifyError


class TelegramNotifier(Notifier):
    name = "telegram"

    def __init__(self, token: str, chat_id: str, *, retries: int = 3, timeout: float = 10.0, session=None):
        self.url = f"https://api.telegram.org/bot{token}/sendMessage"
        self.chat_id = chat_id
        self.retries = retries
        self.timeout = timeout
        self.session = session or requests.Session()

    def send(self, text: str) -> None:
        last_error: Exception | None = None
        for attempt in range(1, self.retries + 1):
            try:
                resp = self.session.post(
                    self.url,
                    json={"chat_id": self.chat_id, "text": text, "disable_web_page_preview": True},
                    timeout=self.timeout,
                )
                if resp.ok:
                    return
                last_error = NotifyError(f"Telegram 응답 {resp.status_code}: {resp.text[:200]}")
            except requests.RequestException as exc:
                last_error = NotifyError(f"Telegram 전송 실패: {exc}")
            if attempt < self.retries:
                time.sleep(2 ** (attempt - 1))
        raise last_error or NotifyError("Telegram 전송 실패")

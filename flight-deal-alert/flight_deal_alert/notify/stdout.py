from __future__ import annotations

import sys

from .base import Notifier


class StdoutNotifier(Notifier):
    """알림 대신 화면에 출력. --dry-run 과 기본 채널로 쓴다."""

    name = "stdout"

    def __init__(self, stream=None):
        self.stream = stream or sys.stdout
        self.sent: list[str] = []

    def send(self, text: str) -> None:
        self.sent.append(text)
        self.stream.write("\n" + "=" * 60 + "\n" + text + "\n" + "=" * 60 + "\n")
        self.stream.flush()

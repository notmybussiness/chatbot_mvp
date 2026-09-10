from __future__ import annotations

from abc import ABC, abstractmethod


class NotifyError(RuntimeError):
    pass


class Notifier(ABC):
    name: str = "base"

    @abstractmethod
    def send(self, text: str) -> None:
        """메시지 한 건 전송. 실패 시 NotifyError. 파이프라인은 이걸 잡고 계속 간다."""

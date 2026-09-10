"""소스 어댑터 인터페이스. 어댑터는 "가져와서 Offer 로 만드는 것"만 한다. 판정은 절대 여기서 하지 않는다."""

from __future__ import annotations

from abc import ABC, abstractmethod
from datetime import datetime

from ..config import AppConfig, RouteConfig
from ..models import Diagnostics, Offer


class SourceError(RuntimeError):
    """소스 실행 실패. 파이프라인은 이걸 잡아서 해당 소스만 실패로 기록하고 계속 간다."""


class NotConfigured(SourceError):
    """키가 없거나 승인이 안 되어 애초에 동작할 수 없는 소스."""


class SourceAdapter(ABC):
    name: str = "base"
    #: 실제 사이트/API 를 호출해 검증한 적이 있는지. 없으면 README 와 diagnose 출력에 표시된다.
    verified: bool = False

    def __init__(self, config: AppConfig, options: dict):
        self.config = config
        self.options = options

    @property
    def interval_minutes(self) -> int:
        return int(self.options.get("interval_minutes", 60))

    @abstractmethod
    def fetch(self, routes: list[RouteConfig], now: datetime) -> list[Offer]:
        """관심 노선에 해당하는 항공권을 정규화해 반환한다. 실패 시 SourceError."""

    def diagnose(self, route: RouteConfig, now: datetime) -> Diagnostics:
        """기본 구현은 fetch 결과만 담는다. 원본 요청/응답을 볼 수 있는 어댑터는 재정의한다."""
        try:
            offers = self.fetch([route], now)
            return Diagnostics(source=self.name, offers=offers, notes=self._verification_note())
        except Exception as exc:  # noqa: BLE001 - 진단은 무엇이 터졌는지 보여주는 게 목적
            return Diagnostics(source=self.name, error=f"{type(exc).__name__}: {exc}", notes=self._verification_note())

    def _verification_note(self) -> list[str]:
        if self.verified:
            return []
        return ["검증 안 됨: 실제 사이트/API 를 호출해 확인한 적이 없습니다. diagnose 결과를 보고 설정을 맞추세요."]

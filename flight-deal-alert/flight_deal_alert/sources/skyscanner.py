"""Skyscanner — 인터페이스 스텁.

Skyscanner Travel API 는 파트너 심사 + 상업 계약이 있어야 키가 나온다. 개인 프로젝트 승인은 사실상 불가.
키가 생기면 이 파일의 fetch 만 채우면 되도록 자리만 잡아둔다. 켜져 있어도 파이프라인은 죽지 않고
"설정 안 됨" 으로 기록된다.
"""

from __future__ import annotations

from datetime import datetime

from ..config import RouteConfig
from ..models import Offer
from .base import NotConfigured, SourceAdapter


class SkyscannerSource(SourceAdapter):
    name = "skyscanner"
    verified = False

    def fetch(self, routes: list[RouteConfig], now: datetime) -> list[Offer]:
        raise NotConfigured(
            "Skyscanner Travel API 는 파트너 승인 키가 필요합니다. "
            "키를 발급받으면 sources/skyscanner.py 의 fetch 를 구현하세요."
        )

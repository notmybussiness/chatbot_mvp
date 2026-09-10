"""소스 레지스트리. config.yaml 의 sources 블록을 읽어 켜진 어댑터만 만든다."""

from __future__ import annotations

import logging

from ..config import AppConfig
from .base import NotConfigured, SourceAdapter, SourceError
from .html_list import HtmlListSource
from .mock import MockSource
from .naver import NaverSource
from .skyscanner import SkyscannerSource
from .travelpayouts import TravelpayoutsSource

__all__ = ["SourceAdapter", "SourceError", "NotConfigured", "build_sources", "KNOWN_SOURCES"]

log = logging.getLogger(__name__)

KNOWN_SOURCES = ("mock", "travelpayouts", "modetour", "ddaeng", "naver", "skyscanner")


def build_sources(config: AppConfig, *, include_disabled: bool = False) -> list[SourceAdapter]:
    sources: list[SourceAdapter] = []
    for name, options in config.sources.items():
        options = options or {}
        if not include_disabled and not options.get("enabled", False):
            continue
        try:
            sources.append(_build(name, config, options))
        except SourceError as exc:
            # 설정이 덜 된 소스 하나 때문에 전체가 못 뜨면 안 된다.
            log.warning("소스 %s 를 만들 수 없어 건너뜁니다: %s", name, exc)
    return sources


def _build(name: str, config: AppConfig, options: dict) -> SourceAdapter:
    kind = options.get("type", name)
    if kind == "mock":
        return MockSource(config, options)
    if kind == "travelpayouts":
        return TravelpayoutsSource(config, options)
    if kind == "naver":
        return NaverSource(config, options)
    if kind == "skyscanner":
        return SkyscannerSource(config, options)
    if kind == "html_list":
        return HtmlListSource(name, config, options)
    raise SourceError(f"알 수 없는 소스 타입: {name} (type={kind}). 가능한 값: mock, travelpayouts, naver, skyscanner, html_list")

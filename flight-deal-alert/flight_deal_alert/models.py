"""파이프라인 전체가 공유하는 데이터 모델. 어댑터는 반드시 Offer 로 정규화해서 넘긴다."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime


@dataclass(frozen=True)
class Offer:
    """소스에 관계없이 동일한 모양으로 정규화된 항공권 한 건."""

    source: str
    origin: str
    destination: str
    depart_date: date
    price_krw: int
    fetched_at: datetime
    return_date: date | None = None
    currency: str = "KRW"
    airline: str = ""
    stops: int = 0
    url: str = ""
    raw_id: str = ""
    tags: tuple[str, ...] = ()

    @property
    def route_key(self) -> str:
        return f"{self.origin}-{self.destination}"

    @property
    def is_round_trip(self) -> bool:
        return self.return_date is not None


@dataclass
class Deal:
    """판정을 통과한 항공권. 왜 특가인지(reasons)를 반드시 들고 다닌다."""

    offer: Offer
    rules: list[str] = field(default_factory=list)
    reasons: list[str] = field(default_factory=list)
    urgency: int = 0


@dataclass
class SourceRun:
    """소스 한 번 실행의 결과. 소스가 조용히 죽어 있는 걸 요약 리포트에서 알아채기 위한 기록."""

    source: str
    started_at: datetime
    finished_at: datetime
    ok: bool
    offer_count: int = 0
    error: str = ""


@dataclass
class Diagnostics:
    """어댑터가 실제로 무엇을 보내고 받았는지. 파싱이 깨졌을 때 추측 없이 원인을 보기 위한 것."""

    source: str
    request: str = ""
    response_head: str = ""
    response_bytes: int = 0
    notes: list[str] = field(default_factory=list)
    offers: list[Offer] = field(default_factory=list)
    error: str = ""

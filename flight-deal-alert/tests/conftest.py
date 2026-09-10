from __future__ import annotations

from datetime import date, datetime, timedelta
from pathlib import Path

import pytest

from flight_deal_alert.config import AppConfig, DetectionConfig, NotifyConfig, RouteConfig
from flight_deal_alert.detector import DealDetector
from flight_deal_alert.models import Offer
from flight_deal_alert.store import Store

NOW = datetime(2026, 9, 10, 12, 0, 0)


@pytest.fixture
def now() -> datetime:
    return NOW


@pytest.fixture
def routes() -> tuple[RouteConfig, ...]:
    return (
        RouteConfig("ICN", "NRT", 250_000, date(2026, 11, 1), date(2027, 2, 28), stay_days=(3, 7)),
        RouteConfig("ICN", "BKK", 350_000, date(2026, 11, 1), date(2027, 2, 28), stay_days=(4, 7)),
    )


@pytest.fixture
def detection() -> DetectionConfig:
    return DetectionConfig(
        relative_window_days=30, relative_discount_pct=20, relative_min_observations=5,
        drop_pct=15, deal_tags=("땡처리", "공동구매", "특가"), imminent_days=14,
        max_alerts_per_route_per_day=2, quiet_hours=(0, 7), quiet_bypass_urgency=90,
    )


@pytest.fixture
def config(routes, detection, tmp_path) -> AppConfig:
    return AppConfig(
        routes=routes, detection=detection, notify=NotifyConfig(channel="stdout"),
        sqlite_path=tmp_path / "t.db", default_origin="ICN",
        destination_aliases={"NRT": ("도쿄", "나리타", "Tokyo"), "BKK": ("방콕",)},
        sources={"mock": {"enabled": True, "interval_minutes": 30}},
        secrets={},
    )


@pytest.fixture
def store() -> Store:
    s = Store(":memory:")
    yield s
    s.close()


@pytest.fixture
def detector(detection, store, config) -> DealDetector:
    return DealDetector(detection, store, config.budgets)


def make_offer(
    price: int, *, route: str = "ICN-NRT", source: str = "test", raw_id: str = "",
    tags: tuple[str, ...] = (), fetched_at: datetime = NOW, depart: date | None = None,
) -> Offer:
    origin, destination = route.split("-")
    return Offer(
        source=source, origin=origin, destination=destination,
        depart_date=depart or date(2026, 12, 20), price_krw=price, fetched_at=fetched_at,
        airline="테스트항공", url=f"https://example.invalid/{raw_id or price}", raw_id=raw_id, tags=tags,
    )


def seed_history(store: Store, prices: list[int], *, route: str = "ICN-NRT", days_ago_start: int = 20) -> None:
    """과거 관측을 하루 간격으로 심는다 (가장 오래된 것부터)."""
    offers = [
        make_offer(p, route=route, raw_id=f"hist-{i}", fetched_at=NOW - timedelta(days=days_ago_start - i))
        for i, p in enumerate(prices)
    ]
    store.save_offers(offers)


FIXTURES = Path(__file__).parent / "fixtures"

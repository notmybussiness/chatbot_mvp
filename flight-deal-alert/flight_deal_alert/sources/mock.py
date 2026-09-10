"""Mock 소스 — 외부 호출 없이 수집→판정→알림 전체 파이프라인을 돌려보기 위한 것.

가격은 (노선, 출발일, 오늘 날짜) 시드로 만들어져 같은 날엔 항상 같고, 날짜가 바뀌면 달라진다.
덕분에 며칠 돌려보면 급락·역대최저·상대기준이 실제로 발동하는 걸 볼 수 있다.
일부 항목은 raw_id 가 날짜와 무관하게 고정되어 있어 "같은 항목의 가격 변화" 규칙도 검증된다.
"""

from __future__ import annotations

import hashlib
import random
from datetime import datetime, timedelta

from ..config import RouteConfig
from ..models import Offer
from .base import SourceAdapter

_AIRLINES = ["대한항공", "아시아나항공", "제주항공", "티웨이항공", "진에어", "에어부산"]


def _seed(*parts: object) -> int:
    digest = hashlib.sha256("|".join(str(p) for p in parts).encode()).digest()
    return int.from_bytes(digest[:8], "big")


class MockSource(SourceAdapter):
    name = "mock"
    verified = True  # 외부 의존이 없으므로 항상 검증된 상태

    def fetch(self, routes: list[RouteConfig], now: datetime) -> list[Offer]:
        offers: list[Offer] = []
        for route in routes:
            offers.extend(self._search_offers(route, now))
            offers.extend(self._tagged_offers(route, now))
        return offers

    def _search_offers(self, route: RouteConfig, now: datetime) -> list[Offer]:
        """검색형 소스 흉내: 노선 기준가 × 날짜별 변동."""
        rng = random.Random(_seed("search", route.key, now.date()))
        base = self._base_price(route)
        # 하루 단위로 ±25% 흔들리되, 가끔(약 1/8) 크게 떨어진다 — 급락/최저가 규칙이 실제로 발동하도록.
        swing = rng.uniform(0.75, 1.25)
        if rng.random() < 0.125:
            swing *= 0.7

        depart = route.depart_from + timedelta(days=rng.randint(0, max(0, (route.depart_to - route.depart_from).days)))
        return_date = None
        if route.stay_days:
            return_date = depart + timedelta(days=rng.randint(*route.stay_days))

        offers = []
        for i, airline in enumerate(rng.sample(_AIRLINES, 3)):
            price = int(base * swing * (1 + 0.08 * i)) // 100 * 100 * route.adults
            offers.append(Offer(
                source=self.name, origin=route.origin, destination=route.destination,
                depart_date=depart, return_date=return_date, price_krw=price,
                airline=airline, stops=0 if i < 2 else 1,
                url=f"https://example.invalid/search/{route.key}/{depart}",
                # 항공사+노선+출발일로 고정 → 날짜가 바뀌어도 같은 항목으로 추적된다.
                raw_id=f"mock-search:{route.key}:{depart}:{airline}",
                fetched_at=now,
            ))
        return offers

    def _tagged_offers(self, route: RouteConfig, now: datetime) -> list[Offer]:
        """땡처리 페이지 흉내: 며칠에 한 번 새 특가가 등장하고 며칠 뒤 사라진다."""
        rng = random.Random(_seed("tagged", route.key, now.date()))
        if rng.random() > 0.4:
            return []
        base = self._base_price(route)
        # 특가는 '등장한 날' 기준으로 raw_id 를 고정해, 첫 실행에서만 new_tagged 로 잡히게 한다.
        appeared = now.date() - timedelta(days=rng.randint(0, 2))
        depart = now.date() + timedelta(days=rng.randint(3, 20))
        return [Offer(
            source=self.name, origin=route.origin, destination=route.destination,
            depart_date=depart, return_date=depart + timedelta(days=4) if route.stay_days else None,
            price_krw=int(base * rng.uniform(0.5, 0.8)) // 100 * 100,
            airline=rng.choice(_AIRLINES), stops=0,
            url=f"https://example.invalid/deal/{route.key}/{appeared}",
            raw_id=f"mock-deal:{route.key}:{appeared}",
            tags=("땡처리",),
            fetched_at=now,
        )]

    @staticmethod
    def _base_price(route: RouteConfig) -> int:
        # 예산의 1.3배를 평상시 가격으로 두면, 예산 이하는 "가끔" 나온다 — 알림이 남발되지 않게.
        return int(route.budget_krw * 1.3)

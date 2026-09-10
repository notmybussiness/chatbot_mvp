"""Travelpayouts(Aviasales) Data API — 공식 경로.

무료 가입 후 토큰 발급. 실시간 검색이 아니라 최근 검색된 캐시 가격을 돌려주므로
"지금 이 가격에 살 수 있다" 보다는 "요즘 이 정도에 나온다" 에 가깝다. 추이·특가 감지에는 충분하다.

문서: https://support.travelpayouts.com/hc/en-us/articles/203956163-Aviasales-Data-API
엔드포인트: GET /aviasales/v3/prices_for_dates
  origin, destination, departure_at(YYYY-MM 또는 YYYY-MM-DD), return_at, one_way, direct,
  currency, market, limit, sorting, unique, token

⚠️ 문서 기준으로 구현했고 이 환경에서는 실제 호출을 해보지 못했다 (검증 안 됨).
응답 필드가 다르면 diagnose 로 원본을 확인하고 _parse 를 손보면 된다.
"""

from __future__ import annotations

import json
import time
from datetime import date, datetime, timedelta

import requests

from ..config import AppConfig, RouteConfig
from ..models import Diagnostics, Offer
from .base import NotConfigured, SourceAdapter, SourceError


class TravelpayoutsSource(SourceAdapter):
    name = "travelpayouts"
    verified = False

    def __init__(self, config: AppConfig, options: dict, session: requests.Session | None = None):
        super().__init__(config, options)
        self.session = session or requests.Session()
        self.base_url = options.get("base_url", "https://api.travelpayouts.com").rstrip("/")
        self.delay = float(options.get("request_delay_sec", 1.0))
        self.timeout = float(options.get("timeout_sec", 20))
        self.token = config.secrets.get("TRAVELPAYOUTS_TOKEN", "")

    def fetch(self, routes: list[RouteConfig], now: datetime) -> list[Offer]:
        if not self.token:
            raise NotConfigured("TRAVELPAYOUTS_TOKEN 이 .env 에 없습니다")
        offers: list[Offer] = []
        first = True
        for route in routes:
            for month in self._months(route):
                if not first:
                    time.sleep(self.delay)
                first = False
                raw = self._get(self._params(route, month))
                offers += self._parse(raw, route, now)
        return offers

    def diagnose(self, route: RouteConfig, now: datetime) -> Diagnostics:
        params = self._params(route, self._months(route)[0])
        diag = Diagnostics(source=self.name, notes=self._verification_note(),
                           request=f"GET {self.base_url}/aviasales/v3/prices_for_dates {dict(params, token='***')}")
        if not self.token:
            diag.error = "TRAVELPAYOUTS_TOKEN 이 .env 에 없습니다"
            return diag
        try:
            raw = self._get(params)
        except Exception as exc:  # noqa: BLE001
            diag.error = f"{type(exc).__name__}: {exc}"
            return diag
        diag.response_head = raw[:2000]
        diag.response_bytes = len(raw)
        diag.offers = self._parse(raw, route, now)
        diag.notes.append(f"정규화 {len(diag.offers)}건")
        return diag

    # ---- 내부 ----

    def _params(self, route: RouteConfig, month: str) -> dict:
        params = {
            "origin": route.origin, "destination": route.destination, "departure_at": month,
            "currency": "krw", "market": "kr", "limit": 30, "sorting": "price", "unique": "false",
            "one_way": "false" if route.stay_days else "true", "token": self.token,
        }
        return params

    def _get(self, params: dict) -> str:
        resp = self.session.get(f"{self.base_url}/aviasales/v3/prices_for_dates", params=params, timeout=self.timeout)
        if resp.status_code == 401:
            raise NotConfigured("Travelpayouts 토큰이 거부되었습니다 (401)")
        if not resp.ok:
            raise SourceError(f"[travelpayouts] HTTP {resp.status_code}: {resp.text[:200]}")
        return resp.text

    @staticmethod
    def _months(route: RouteConfig) -> list[str]:
        months = []
        cursor = route.depart_from.replace(day=1)
        while cursor <= route.depart_to:
            months.append(cursor.strftime("%Y-%m"))
            cursor = (cursor + timedelta(days=32)).replace(day=1)
        return months

    def _parse(self, raw: str, route: RouteConfig, now: datetime) -> list[Offer]:
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise SourceError(f"[travelpayouts] JSON 파싱 실패: {exc}") from exc
        if not payload.get("success", True):
            raise SourceError(f"[travelpayouts] API 오류: {payload.get('error')}")

        offers = []
        for row in payload.get("data") or []:
            price = row.get("price")
            depart_raw = row.get("departure_at")
            if price is None or not depart_raw:
                continue
            depart = date.fromisoformat(str(depart_raw)[:10])
            if not (route.depart_from <= depart <= route.depart_to):
                continue
            ret_raw = row.get("return_at")
            ret = date.fromisoformat(str(ret_raw)[:10]) if ret_raw else None
            link = row.get("link") or ""
            if link.startswith("/"):
                link = "https://www.aviasales.com" + link
            offers.append(Offer(
                source=self.name, origin=route.origin, destination=route.destination,
                depart_date=depart, return_date=ret, price_krw=int(price), currency="KRW",
                airline=str(row.get("airline") or ""), stops=int(row.get("transfers") or 0), url=link,
                raw_id=f"travelpayouts:{route.key}:{depart}:{ret}:{row.get('airline')}:{row.get('flight_number', '')}",
                fetched_at=now,
            ))
        return offers

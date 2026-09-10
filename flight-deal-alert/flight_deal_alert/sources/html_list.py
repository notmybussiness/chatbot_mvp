"""설정 기반 HTML 목록 페이지 어댑터 — 모두투어 공동구매, 땡처리 페이지 등에 공통으로 쓴다.

⚠️ 검증 안 됨. 실제 페이지 구조는 확인하지 못했다. config.yaml 의 item_selector / fields 를
실제 페이지에서 DevTools 로 확인해 채워야 한다. `diagnose` 가 "선택자에 몇 개 걸렸는지",
"그중 몇 개가 관심 노선으로 매핑됐는지" 를 알려주므로 그걸 보고 맞추면 된다.

목록에는 "도쿄 왕복 189,000원" 처럼 도시명만 있고 공항코드가 없다. destination_aliases 로 매핑하며,
어느 노선에도 안 걸리는 항목은 버린다(진단에는 개수가 남는다).
"""

from __future__ import annotations

import re
import time
from datetime import date, datetime
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

from ..config import AppConfig, RouteConfig
from ..models import Diagnostics, Offer
from .base import SourceAdapter, SourceError

_DEFAULT_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"


class HtmlListSource(SourceAdapter):
    verified = False

    def __init__(self, name: str, config: AppConfig, options: dict, session: requests.Session | None = None):
        super().__init__(config, options)
        self.name = name
        self.session = session or requests.Session()
        self.url: str = options.get("url", "")
        self.item_selector: str = options.get("item_selector", "")
        self.fields: dict = options.get("fields", {})
        self.static_tags: tuple[str, ...] = tuple(options.get("tags", ()))
        self.delay = float(options.get("request_delay_sec", 2.0))
        self.timeout = float(options.get("timeout_sec", 20))
        if not self.url or not self.item_selector:
            raise SourceError(f"[{name}] url 과 item_selector 가 설정에 있어야 합니다")

    # ---- 수집 ----

    def fetch(self, routes: list[RouteConfig], now: datetime) -> list[Offer]:
        html = self._get()
        offers, _ = self.parse(html, routes, now)
        return offers

    def diagnose(self, route: RouteConfig, now: datetime) -> Diagnostics:
        diag = Diagnostics(source=self.name, request=f"GET {self.url}", notes=self._verification_note())
        try:
            html = self._get()
        except Exception as exc:  # noqa: BLE001
            diag.error = f"{type(exc).__name__}: {exc}"
            return diag
        diag.response_head = html[:2000]
        diag.response_bytes = len(html)
        offers, notes = self.parse(html, list(self.config.routes), now)
        diag.offers = offers
        diag.notes += notes
        return diag

    def _get(self) -> str:
        time.sleep(self.delay)
        resp = self.session.get(
            self.url, headers={"User-Agent": _DEFAULT_UA, "Accept-Language": "ko-KR,ko;q=0.9"}, timeout=self.timeout
        )
        if not resp.ok:
            raise SourceError(f"[{self.name}] HTTP {resp.status_code}")
        resp.encoding = resp.apparent_encoding or resp.encoding
        return resp.text

    # ---- 파싱 (네트워크와 분리해 테스트 가능하게) ----

    def parse(self, html: str, routes: list[RouteConfig], now: datetime) -> tuple[list[Offer], list[str]]:
        soup = BeautifulSoup(html, "html.parser")
        items = soup.select(self.item_selector)
        notes = [f"item_selector '{self.item_selector}' 에 {len(items)}개 걸림"]
        if not items:
            notes.append("0개면 선택자가 틀렸거나 페이지가 JS 로 렌더링됩니다 (응답 앞부분을 확인하세요)")
            return [], notes

        offers: list[Offer] = []
        unmapped = 0
        no_price = 0
        for idx, item in enumerate(items):
            title = self._field(item, "title") or ""
            price_text = self._field(item, "price")
            price = self._to_int(price_text)
            if price is None:
                no_price += 1
                continue

            route = self._match_route(title, routes)
            if route is None:
                unmapped += 1
                continue

            depart = self._to_date(self._field(item, "depart")) or now.date()
            href = self._field(item, "url") or ""
            url = urljoin(self.url, href) if href else self.url
            tags = self.static_tags + self._extra_tags(title)

            offers.append(Offer(
                source=self.name, origin=route.origin, destination=route.destination,
                depart_date=depart, price_krw=price, airline=self._field(item, "airline") or "",
                stops=0, url=url,
                # 링크가 있으면 링크가 곧 항목 식별자. 없으면 제목+가격으로 대체.
                raw_id=f"{self.name}:{href or title}:{depart}",
                tags=tags, fetched_at=now,
            ))

        notes.append(f"가격 파싱 실패 {no_price}개 · 관심 노선 미매핑 {unmapped}개 · 정규화 {len(offers)}개")
        if unmapped and not offers:
            notes.append("전부 미매핑이면 destination_aliases 에 목록에 쓰이는 도시명을 추가하세요")
        return offers, notes

    def _field(self, item, name: str) -> str | None:
        spec = self.fields.get(name)
        if not spec:
            return None
        node = item.select_one(spec["selector"]) if spec.get("selector") else item
        if node is None:
            return None
        value = node.get(spec["attr"], "") if spec.get("attr") else node.get_text(" ", strip=True)
        if spec.get("regex"):
            m = re.search(spec["regex"], value)
            value = m.group(0) if m else ""
        return value.strip() or None

    def _match_route(self, title: str, routes: list[RouteConfig]) -> RouteConfig | None:
        for route in routes:
            names = (route.destination,) + self.config.destination_aliases.get(route.destination, ())
            if any(n and n.lower() in title.lower() for n in names):
                return route
        return None

    def _extra_tags(self, title: str) -> tuple[str, ...]:
        return tuple(t for t in self.config.detection.deal_tags if t in title and t not in self.static_tags)

    @staticmethod
    def _to_int(text: str | None) -> int | None:
        if not text:
            return None
        digits = re.sub(r"[^0-9]", "", text)
        return int(digits) if digits else None

    @staticmethod
    def _to_date(text: str | None) -> date | None:
        if not text:
            return None
        m = re.search(r"(\d{4})[-./](\d{1,2})[-./](\d{1,2})", text)
        if not m:
            return None
        try:
            return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
        except ValueError:
            return None

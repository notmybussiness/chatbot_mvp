"""네이버 항공권 — 비공식 내부 API.

⚠️ 검증 안 됨 + 배포 시 막힐 수 있음.
- 네이버는 공개 API 가 없다. 이 어댑터는 웹 프론트가 쓰는 내부 엔드포인트를 직접 부른다.
- 요청 스키마를 알 수 없어 본문을 `naver_request_template.json` 으로 뺐다. DevTools 캡처로 교체하면 된다.
- 응답 스키마도 알 수 없어 특정 경로에 기대지 않고 JSON 트리를 훑어 "가격처럼 보이는 필드" 를 찾는다.
  후보 키는 config 의 price_keys 등으로 넓힐 수 있다.
- 클라우드(AWS 등) IP 대역이 차단된다. 집 PC/홈서버에서 돌리는 걸 전제로 한다.
- 이 저장소는 IP 우회·캡차 우회 같은 차단 회피 장치를 넣지 않는다. 막히면 실패로 기록하고 끝.
"""

from __future__ import annotations

import json
import re
import time
from datetime import datetime, timedelta
from pathlib import Path

import requests

from ..config import AppConfig, RouteConfig
from ..models import Diagnostics, Offer
from .base import SourceAdapter, SourceError

_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
_CABIN = {"economy": "Y", "business": "C", "first": "F"}
_PLACEHOLDER = re.compile(r"\{\{[^}]*}}")


class NaverSource(SourceAdapter):
    name = "naver"
    verified = False

    def __init__(self, config: AppConfig, options: dict, session: requests.Session | None = None):
        super().__init__(config, options)
        self.session = session or requests.Session()
        self.base_url = options.get("base_url", "https://flight-api.naver.com").rstrip("/")
        self.search_path = options.get("search_path", "/flight/international/searchFlights")
        self.referer = options.get("referer", "https://flight.naver.com/")
        self.delay = float(options.get("request_delay_sec", 5.0))
        self.timeout = float(options.get("timeout_sec", 30))
        self.template_path = Path(options.get("request_template", Path(__file__).with_name("naver_request_template.json")))
        self.extractor = OfferExtractor(
            price_keys=options.get("price_keys", ["totalFare", "totalPrice", "adultFare", "lowestFare", "fare", "price", "amount"]),
            airline_keys=options.get("airline_keys", ["airlineName", "airline", "carrierName", "airlineCode", "carrier"]),
            stops_keys=options.get("stops_keys", ["stopCount", "stops", "viaCount", "transitCount"]),
            link_keys=options.get("link_keys", ["deepLink", "bookingUrl", "link", "url"]),
            min_price=int(options.get("min_price", 10_000)),
        )

    # ---- 수집 ----

    def fetch(self, routes: list[RouteConfig], now: datetime) -> list[Offer]:
        offers: list[Offer] = []
        first = True
        for route in routes:
            for depart, ret in self._sample_dates(route, now):
                if not first:
                    time.sleep(self.delay)
                first = False
                body = self.render_request(route, depart, ret)
                raw = self._post(body)
                found, _ = self.extractor.extract_text(raw)
                offers += [
                    Offer(
                        source=self.name, origin=route.origin, destination=route.destination,
                        depart_date=depart, return_date=ret, price_krw=price, airline=airline, stops=stops,
                        url=link or f"https://flight.naver.com/flights/international/{route.origin}-{route.destination}-{depart:%Y%m%d}",
                        raw_id=f"naver:{route.key}:{depart}:{ret}:{airline}", fetched_at=now,
                    )
                    for price, airline, stops, link in found
                ]
        return offers

    def diagnose(self, route: RouteConfig, now: datetime) -> Diagnostics:
        depart, ret = self._sample_dates(route, now)[0]
        diag = Diagnostics(source=self.name, notes=self._verification_note())
        try:
            diag.request = self.render_request(route, depart, ret)
            raw = self._post(diag.request)
        except Exception as exc:  # noqa: BLE001
            diag.error = f"{type(exc).__name__}: {exc}"
            return diag
        diag.response_head = raw[:2000]
        diag.response_bytes = len(raw)
        found, matched_keys = self.extractor.extract_text(raw)
        diag.notes.append(f"가격으로 인식된 필드명: {matched_keys or '없음 — price_keys 를 넓히세요'}")
        diag.offers = [
            Offer(source=self.name, origin=route.origin, destination=route.destination, depart_date=depart,
                  return_date=ret, price_krw=p, airline=a, stops=s, url=l or "", fetched_at=now)
            for p, a, s, l in found
        ]
        return diag

    def _post(self, body: str) -> str:
        resp = self.session.post(
            self.base_url + self.search_path, data=body.encode("utf-8"),
            headers={
                "User-Agent": _UA, "Referer": self.referer, "Origin": self.referer.rstrip("/"),
                "Content-Type": "application/json", "Accept": "text/event-stream, application/json",
                "Accept-Language": "ko-KR,ko;q=0.9",
            },
            timeout=self.timeout,
        )
        if not resp.ok:
            raise SourceError(f"[naver] HTTP {resp.status_code} — 클라우드 IP 차단이거나 엔드포인트가 바뀌었을 수 있습니다")
        return resp.text

    def _sample_dates(self, route: RouteConfig, now: datetime) -> list[tuple]:
        """기간 창 전체를 매번 조회하면 요청이 너무 많다. 창을 3등분해 대표 날짜만 본다."""
        span = (route.depart_to - route.depart_from).days
        points = sorted({route.depart_from + timedelta(days=span * k // 3) for k in range(3)} if span > 0 else {route.depart_from})
        out = []
        for d in points:
            if d < now.date():
                continue
            ret = d + timedelta(days=route.stay_days[0]) if route.stay_days else None
            out.append((d, ret))
        return out or [(max(route.depart_from, now.date()), None)]

    # ---- 요청 본문 (템플릿) ----

    def render_request(self, route: RouteConfig, depart, ret) -> str:
        template = self.template_path.read_text(encoding="utf-8")
        values = {
            "origin": route.origin, "destination": route.destination,
            "departureDate": depart.strftime("%Y%m%d"), "departureDateDash": depart.isoformat(),
            "returnDate": ret.strftime("%Y%m%d") if ret else "", "returnDateDash": ret.isoformat() if ret else "",
            "adults": str(route.adults), "cabin": _CABIN.get(route.cabin.lower(), "Y"),
            "tripType": "RT" if ret else "OW",
        }
        rendered = template
        for key, value in values.items():
            rendered = rendered.replace("{{%s}}" % key, value)
        leftover = _PLACEHOLDER.search(rendered)
        if leftover:
            raise SourceError(f"[naver] 템플릿에 알 수 없는 플레이스홀더: {leftover.group(0)}")
        try:
            node = json.loads(rendered)
        except json.JSONDecodeError as exc:
            raise SourceError(f"[naver] 요청 템플릿이 유효한 JSON 이 아닙니다: {exc}") from exc

        # 왕복이면 템플릿의 _return_leg 를 fly 에 붙이고, 설명용 '_' 키는 전송 전에 지운다.
        if isinstance(node, dict):
            if ret and isinstance(node.get("_return_leg"), dict) and isinstance(node.get("fly"), list):
                node["fly"].append(node["_return_leg"])
            for key in [k for k in node if k.startswith("_")]:
                node.pop(key)
        return json.dumps(node, ensure_ascii=False)


class OfferExtractor:
    """스키마를 모르는 JSON 에서 (가격, 항공사, 경유수, 링크) 를 뽑는다.

    트리 전체를 훑으며 가격 후보 키를 가진 객체를 찾고, 항공사·링크는 상위 노드에서 물려받는다.
    운임 객체가 여정 객체 아래 중첩되는 흔한 형태를 그대로 소화한다.
    """

    def __init__(self, *, price_keys, airline_keys, stops_keys, link_keys, min_price: int):
        self.price_keys = list(price_keys)
        self.airline_keys = list(airline_keys)
        self.stops_keys = list(stops_keys)
        self.link_keys = list(link_keys)
        self.min_price = min_price

    def extract_text(self, raw: str) -> tuple[list[tuple[int, str, int, str | None]], list[str]]:
        """SSE(`data: {...}` 여러 줄) 또는 순수 JSON 모두 처리."""
        chunks = [ln[5:].strip() for ln in raw.splitlines() if ln.strip().startswith("data:")]
        chunks = [c for c in chunks if c and c != "[DONE]"] or [raw.strip()]
        found: list[tuple[int, str, int, str | None]] = []
        keys: list[str] = []
        for chunk in chunks:
            try:
                node = json.loads(chunk)
            except json.JSONDecodeError:
                continue
            f, k = self.extract(node)
            found += f
            keys += [x for x in k if x not in keys]
        # 같은 운임이 상위/하위에 중복 등장할 수 있어 정리
        uniq = list({(p, a): (p, a, s, l) for p, a, s, l in found}.values())
        return sorted(uniq, key=lambda t: t[0]), keys

    def extract(self, root) -> tuple[list[tuple[int, str, int, str | None]], list[str]]:
        found: list[tuple[int, str, int, str | None]] = []
        keys: list[str] = []

        def walk(node, airline: str, stops: int, link: str | None):
            if isinstance(node, list):
                for child in node:
                    walk(child, airline, stops, link)
                return
            if not isinstance(node, dict):
                return
            airline = self._text(node, self.airline_keys) or airline
            link = self._text(node, self.link_keys) or link
            stops_here = self._int(node, self.stops_keys)
            stops = stops_here if stops_here is not None else stops
            hit = self._price(node)
            if hit:
                key, price = hit
                if key not in keys:
                    keys.append(key)
                found.append((price, airline or "미상", stops, link))
            for child in node.values():
                walk(child, airline, stops, link)

        walk(root, "", 0, None)
        return found, keys

    def _price(self, node: dict):
        for key in self.price_keys:
            v = node.get(key)
            if isinstance(v, bool):
                continue
            if isinstance(v, (int, float)):
                price = int(v)
            elif isinstance(v, str):
                digits = re.sub(r"[^0-9]", "", v)
                price = int(digits) if digits else None
            else:
                continue
            if price is not None and price >= self.min_price:
                return key, price
        return None

    @staticmethod
    def _text(node: dict, keys) -> str | None:
        for key in keys:
            v = node.get(key)
            if isinstance(v, str) and v.strip():
                return v.strip()
        return None

    @staticmethod
    def _int(node: dict, keys) -> int | None:
        for key in keys:
            v = node.get(key)
            if isinstance(v, int) and not isinstance(v, bool):
                return v
        return None

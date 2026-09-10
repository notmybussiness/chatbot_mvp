"""특가 판정과 스팸 방지.

판정은 "이번 실행에서 가져온 offer" 를 "이번 실행 이전에 저장된 데이터" 와 비교한다.
그래서 파이프라인은 반드시 판정 → 저장 순서로 돌린다. 저장부터 하면 역대 최저가는 항상 참이 된다.

규칙 5개 (하나라도 걸리면 후보):
  absolute   price ≤ 노선 예산
  relative   price ≤ 최근 N일 중앙값 × (1 − X%)   — 관측이 충분할 때만
  all_time   price < 관측 이래 최저가              — 이전 관측이 있을 때만
  new_tagged 특가 태그가 붙은 항목이 처음 등장
  drop       같은 항목이 직전 대비 Y% 이상 하락

스팸 방지 (알림이 쓸모없어지지 않게):
  같은 항목은 가격이 더 내려가지 않는 한 재알림 금지
  노선당 하루 알림 수 제한
  배치 안 중복은 최저가 하나만
"""

from __future__ import annotations

import statistics
from datetime import datetime, timedelta

from .config import DetectionConfig
from .models import Deal, Offer
from .store import Store

RULE_ABSOLUTE = "absolute"
RULE_RELATIVE = "relative"
RULE_ALL_TIME = "all_time"
RULE_NEW_TAGGED = "new_tagged"
RULE_DROP = "drop"


def _won(value: int) -> str:
    return f"{value:,}원"


class DealDetector:
    def __init__(self, cfg: DetectionConfig, store: Store, budgets: dict[str, int]):
        self.cfg = cfg
        self.store = store
        self.budgets = budgets

    # ---- 1단계: 규칙 판정 ----

    def evaluate(self, offers: list[Offer], now: datetime) -> list[Deal]:
        """규칙을 통과한 offer 를 Deal 로 만든다. 스팸 방지는 아직 적용하지 않는다."""
        deals: list[Deal] = []
        for offer in offers:
            deal = self._evaluate_one(offer, now)
            if deal.rules:
                deals.append(deal)
        return deals

    def _evaluate_one(self, offer: Offer, now: datetime) -> Deal:
        deal = Deal(offer=offer)
        price = offer.price_krw

        budget = self.budgets.get(offer.route_key)
        if budget is not None and price <= budget:
            deal.rules.append(RULE_ABSOLUTE)
            deal.reasons.append(f"예산 {_won(budget)} 이하")

        window_start = now - timedelta(days=self.cfg.relative_window_days)
        history = self.store.prices_in_window(offer.route_key, window_start, now)
        if len(history) >= self.cfg.relative_min_observations:
            median = statistics.median(history)
            threshold = median * (1 - self.cfg.relative_discount_pct / 100)
            if price <= threshold:
                pct = round((1 - price / median) * 100)
                deal.rules.append(RULE_RELATIVE)
                deal.reasons.append(
                    f"최근 {self.cfg.relative_window_days}일 중앙값 {_won(int(median))} 대비 {pct}% 저렴"
                )

        prior_min = self.store.all_time_min(offer.route_key, now)
        if prior_min is not None and price < prior_min:
            deal.rules.append(RULE_ALL_TIME)
            deal.reasons.append(f"관측 이래 최저 (이전 최저 {_won(prior_min)})")

        prev_price = self.store.last_price(offer.source, offer.raw_id, now) if offer.raw_id else None
        deal_tags = [t for t in offer.tags if t in self.cfg.deal_tags]
        if deal_tags and offer.raw_id and prev_price is None:
            deal.rules.append(RULE_NEW_TAGGED)
            deal.reasons.append(f"{'/'.join(deal_tags)} 신규 등장")

        if prev_price is not None and prev_price > 0:
            drop_threshold = prev_price * (1 - self.cfg.drop_pct / 100)
            if price <= drop_threshold:
                pct = round((1 - price / prev_price) * 100)
                deal.rules.append(RULE_DROP)
                deal.reasons.append(f"직전 {_won(prev_price)} → {pct}% 급락")

        if deal.rules:
            deal.urgency = self._urgency(deal, now)
        return deal

    def _urgency(self, deal: Deal, now: datetime) -> int:
        rules = set(deal.rules)
        score = 0
        if RULE_ABSOLUTE in rules and RULE_RELATIVE in rules:
            score = 90
        elif RULE_ALL_TIME in rules:
            score = 80
        elif RULE_NEW_TAGGED in rules:
            days_left = (deal.offer.depart_date - now.date()).days
            score = 75 if 0 <= days_left <= self.cfg.imminent_days else 60
        elif RULE_ABSOLUTE in rules:
            score = 60
        elif RULE_DROP in rules:
            score = 55
        elif RULE_RELATIVE in rules:
            score = 50
        # 규칙이 겹칠수록 조금씩 가산. 규칙 하나짜리와 구분되게.
        score += 5 * (len(rules) - 1)
        return min(score, 100)

    # ---- 2단계: 스팸 방지 ----

    def suppress(self, deals: list[Deal], now: datetime) -> tuple[list[Deal], list[tuple[Deal, str]]]:
        """보낼 것과 억제된 것(사유 포함)으로 나눈다."""
        # 배치 안 같은 항목은 최저가 하나만 남긴다.
        best_by_key: dict[tuple[str, str], Deal] = {}
        for deal in deals:
            key = (deal.offer.source, deal.offer.raw_id or f"_{id(deal)}")
            if key not in best_by_key or deal.offer.price_krw < best_by_key[key].offer.price_krw:
                best_by_key[key] = deal

        kept: list[Deal] = []
        dropped: list[tuple[Deal, str]] = []
        sent_today: dict[str, int] = {}

        for deal in sorted(best_by_key.values(), key=lambda d: (-d.urgency, d.offer.price_krw)):
            offer = deal.offer

            if offer.raw_id:
                last = self.store.last_alert_price(offer.source, offer.raw_id)
                if last is not None and offer.price_krw >= last:
                    dropped.append((deal, f"이미 {_won(last)} 에 알림 보냄, 더 싸지지 않음"))
                    continue

            route = offer.route_key
            if route not in sent_today:
                sent_today[route] = self.store.alerts_on_day(route, now.date())
            if sent_today[route] >= self.cfg.max_alerts_per_route_per_day:
                dropped.append((deal, f"노선 일일 알림 한도 {self.cfg.max_alerts_per_route_per_day}건 초과"))
                continue

            sent_today[route] += 1
            kept.append(deal)

        return kept, dropped

    # ---- 조용한 시간대 ----

    def is_quiet(self, now: datetime) -> bool:
        if not self.cfg.quiet_hours:
            return False
        start, end = self.cfg.quiet_hours
        hour = now.hour
        return start <= hour < end if start <= end else (hour >= start or hour < end)

    def should_queue(self, deal: Deal, now: datetime) -> bool:
        return self.is_quiet(now) and deal.urgency < self.cfg.quiet_bypass_urgency

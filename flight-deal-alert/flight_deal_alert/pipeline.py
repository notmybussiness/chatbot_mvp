"""한 번의 실행: 소스별로 수집 → 판정 → 저장 → 알림.

순서가 중요하다. 판정은 "이전에 저장된 데이터" 와 비교해야 하므로 저장보다 먼저 한다.
한 소스가 터져도 다른 소스는 계속 돌고, 실패는 source_runs 에 남는다.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from .config import AppConfig
from .detector import DealDetector
from .models import Deal, SourceRun
from .notify.base import Notifier, NotifyError
from .notify.format import format_deal, format_digest, format_summary
from .sources.base import NotConfigured, SourceAdapter
from .store import Store

log = logging.getLogger(__name__)


@dataclass
class RunResult:
    offers: int = 0
    deals: int = 0
    sent: int = 0
    queued: int = 0
    suppressed: int = 0
    failed_sources: list[str] = field(default_factory=list)
    skipped_sources: list[str] = field(default_factory=list)


class Pipeline:
    def __init__(
        self, config: AppConfig, store: Store, sources: list[SourceAdapter], notifier: Notifier,
        *, source_gap_sec: float = 0.0,
    ):
        self.config = config
        self.source_gap_sec = source_gap_sec
        self.store = store
        self.sources = sources
        self.notifier = notifier
        self.detector = DealDetector(config.detection, store, config.budgets)

    # ---- 실행 ----

    def run_once(self, now: datetime | None = None, *, only: str | None = None, force: bool = False) -> RunResult:
        now = now or datetime.now()
        result = RunResult()

        ran_any = False
        for source in self.sources:
            if only and source.name != only:
                continue
            if not force and not self._is_due(source, now):
                result.skipped_sources.append(source.name)
                continue
            # 여러 사이트를 연달아 두드리지 않도록 소스 사이에 간격을 둔다.
            if ran_any:
                sleep_between_sources(self.source_gap_sec)
            self._run_source(source, now, result)
            ran_any = True

        # 조용한 시간이 끝났으면 모아둔 알림을 내보낸다.
        if not self.detector.is_quiet(now):
            result.sent += self.flush_queued(now)
        return result

    def _is_due(self, source: SourceAdapter, now: datetime) -> bool:
        last = self.store.last_run(source.name)
        if last is None:
            return True
        return now - last.started_at >= timedelta(minutes=source.interval_minutes)

    def _run_source(self, source: SourceAdapter, now: datetime, result: RunResult) -> None:
        # 실행 기록은 파이프라인의 now 기준. 벽시계를 쓰면 백테스트·테스트에서 주기 판정이 어긋난다.
        started = now
        clock = datetime.now()

        def finished() -> datetime:
            return now + (datetime.now() - clock)
        try:
            offers = source.fetch(list(self.config.routes), now)
        except NotConfigured as exc:
            log.info("[%s] 설정 안 됨, 건너뜀: %s", source.name, exc)
            self.store.record_run(SourceRun(source.name, started, finished(), ok=False, error=f"설정 안 됨: {exc}"))
            result.failed_sources.append(source.name)
            return
        except Exception as exc:  # noqa: BLE001 - 한 소스의 실패가 나머지를 막으면 안 된다
            log.warning("[%s] 수집 실패: %s", source.name, exc)
            self.store.record_run(SourceRun(source.name, started, finished(), ok=False, error=f"{type(exc).__name__}: {exc}"))
            result.failed_sources.append(source.name)
            return

        # 판정 → 저장 순서. 저장을 먼저 하면 '역대 최저' 가 항상 참이 된다.
        deals = self.detector.evaluate(offers, now)
        kept, dropped = self.detector.suppress(deals, now)
        for deal, why in dropped:
            log.info("[%s] 억제: %s %s — %s", source.name, deal.offer.route_key, deal.offer.price_krw, why)

        self.store.save_offers(offers)
        self.store.record_run(SourceRun(source.name, started, finished(), ok=True, offer_count=len(offers)))

        result.offers += len(offers)
        result.deals += len(deals)
        result.suppressed += len(dropped)

        for deal in kept:
            if self.detector.should_queue(deal, now):
                self._persist(deal, status="queued", now=now)
                result.queued += 1
            else:
                self._deliver(deal, now)
                result.sent += 1

    def _deliver(self, deal: Deal, now: datetime) -> None:
        message = format_deal(deal)
        try:
            self.notifier.send(message)
            self._persist(deal, status="sent", now=now, message=message)
        except NotifyError as exc:
            # 전송이 실패해도 판정 기록은 남긴다. 다음 실행에서 더 싸지지 않으면 재알림되지 않는 건 감수.
            log.error("알림 전송 실패, queued 로 보관: %s", exc)
            self._persist(deal, status="queued", now=now, message=message)

    def _persist(self, deal: Deal, *, status: str, now: datetime, message: str | None = None) -> None:
        o = deal.offer
        self.store.save_alert(
            source=o.source, raw_id=o.raw_id, route_key=o.route_key, price_krw=o.price_krw,
            urgency=deal.urgency, rules=deal.rules, message=message or format_deal(deal),
            status=status, created_at=now,
        )

    def flush_queued(self, now: datetime) -> int:
        """모아둔(queued) 알림을 한 통으로 보낸다."""
        rows = self.store.queued_alerts()
        if not rows:
            return 0
        text = "📬 모아둔 알림 %d건\n\n" % len(rows) + "\n\n".join(r["message"] for r in rows)
        try:
            self.notifier.send(text)
        except NotifyError as exc:
            log.error("모아둔 알림 전송 실패: %s", exc)
            return 0
        self.store.mark_sent([r["id"] for r in rows], now)
        return len(rows)

    # ---- 요약 ----

    def daily_summary(self, now: datetime | None = None, *, send: bool = True) -> str:
        now = now or datetime.now()
        since = now - timedelta(days=1)
        runs = self.store.runs_since(since)
        ran = {r.source for r in runs}
        stale = [s.name for s in self.sources if s.name not in ran]
        text = format_summary(
            now=now,
            offer_count=self.store.offer_count_since(since),
            alert_count=self.store.alert_count_since(since),
            runs=runs,
            stale_sources=stale,
        )
        if send:
            try:
                self.notifier.send(text)
            except NotifyError as exc:
                log.error("요약 전송 실패: %s", exc)
        return text

    # ---- 백테스트 ----

    def backtest(self, since: datetime, until: datetime | None = None) -> list[tuple[datetime, Deal]]:
        """저장된 관측을 시간순으로 다시 흘려보내 판정만 재실행한다. 알림은 보내지 않는다.

        규칙이나 임계값을 바꿨을 때 '과거였다면 뭘 잡았을까' 를 보는 용도.
        """
        until = until or datetime.now()
        offers = self.store.offers_between(since, until)
        # 같은 fetched_at 끼리 한 배치로 묶어 실제 실행과 같은 조건을 만든다.
        batches: dict[datetime, list] = {}
        for o in offers:
            batches.setdefault(o.fetched_at, []).append(o)

        found: list[tuple[datetime, Deal]] = []
        for ts in sorted(batches):
            for deal in self.detector.evaluate(batches[ts], ts):
                found.append((ts, deal))
        return found


def sleep_between_sources(seconds: float) -> None:
    if seconds > 0:
        time.sleep(seconds)

"""상주 실행. 소스별 주기는 파이프라인이 source_runs 를 보고 판단하므로 여기선 짧게 깨어나 물어보기만 한다."""

from __future__ import annotations

import logging
import time
from datetime import date, datetime

from .pipeline import Pipeline

log = logging.getLogger(__name__)


class Daemon:
    def __init__(self, pipeline: Pipeline, *, summary_hour: int, tick_seconds: int = 60):
        self.pipeline = pipeline
        self.summary_hour = summary_hour
        self.tick_seconds = tick_seconds
        self._summary_sent_on: date | None = None

    def tick(self, now: datetime | None = None) -> None:
        now = now or datetime.now()
        result = self.pipeline.run_once(now)
        if result.offers or result.failed_sources:
            log.info(
                "실행: 관측 %d · 후보 %d · 전송 %d · 보류 %d · 억제 %d · 실패 %s",
                result.offers, result.deals, result.sent, result.queued, result.suppressed, result.failed_sources or "-",
            )
        if now.hour == self.summary_hour and self._summary_sent_on != now.date():
            self.pipeline.daily_summary(now)
            self._summary_sent_on = now.date()

    def run_forever(self) -> None:
        log.info("데몬 시작 (tick %ds, 요약 %02d시)", self.tick_seconds, self.summary_hour)
        while True:
            try:
                self.tick()
            except Exception:  # noqa: BLE001 - 데몬은 무슨 일이 있어도 죽지 않는다
                log.exception("tick 중 예외")
            time.sleep(self.tick_seconds)

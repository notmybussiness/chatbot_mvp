from datetime import datetime, timedelta

from flight_deal_alert.config import RouteConfig
from flight_deal_alert.models import Offer
from flight_deal_alert.notify.stdout import StdoutNotifier
from flight_deal_alert.pipeline import Pipeline
from flight_deal_alert.sources.base import NotConfigured, SourceAdapter
from flight_deal_alert.sources.mock import MockSource

from conftest import make_offer


class FixedSource(SourceAdapter):
    """테스트용: 넣어준 offer 를 그대로 돌려준다."""
    name = "fixed"
    verified = True

    def __init__(self, config, offers, name="fixed"):
        super().__init__(config, {"interval_minutes": 60})
        self.offers = offers
        self.name = name

    def fetch(self, routes, now):
        return [o for o in self.offers]


class BrokenSource(SourceAdapter):
    name = "broken"

    def fetch(self, routes, now):
        raise RuntimeError("site changed")


class UnconfiguredSource(SourceAdapter):
    name = "unconfigured"

    def fetch(self, routes, now):
        raise NotConfigured("no key")


def build(config, store, sources, **kw):
    notifier = StdoutNotifier(stream=open("/dev/null", "w"))
    return Pipeline(config, store, sources, notifier, **kw), notifier


def test_end_to_end_with_mock(config, store, now):
    pipeline, notifier = build(config, store, [MockSource(config, {})])
    result = pipeline.run_once(now)
    assert result.offers > 0 and not result.failed_sources
    assert store.offer_count_since(now - timedelta(minutes=1)) == result.offers
    assert len(notifier.sent) == result.sent
    assert store.last_run("mock").ok


def test_evaluation_happens_before_saving(config, store, now):
    # 저장을 먼저 하면 '역대 최저' 가 항상 참이 된다. 첫 실행에서는 최저가 규칙이 절대 안 걸려야 한다.
    pipeline, _ = build(config, store, [FixedSource(config, [make_offer(300_000, raw_id="a", source="fixed")])])
    pipeline.run_once(now)
    assert store.alert_count_since(now - timedelta(minutes=1)) == 0


def test_second_run_same_day_is_suppressed(config, store, now):
    src = FixedSource(config, [make_offer(240_000, raw_id="a", source="fixed")])
    pipeline, notifier = build(config, store, [src])
    assert pipeline.run_once(now, force=True).sent == 1
    later = pipeline.run_once(now + timedelta(hours=2), force=True)
    assert later.sent == 0 and later.suppressed == 1
    assert len(notifier.sent) == 1


def test_broken_source_is_isolated(config, store, now):
    good = FixedSource(config, [make_offer(240_000, raw_id="a", source="fixed")])
    pipeline, _ = build(config, store, [BrokenSource(config, {}), good, UnconfiguredSource(config, {})])
    result = pipeline.run_once(now)
    assert result.failed_sources == ["broken", "unconfigured"]
    assert result.sent == 1
    assert "site changed" in store.last_run("broken").error
    assert "설정 안 됨" in store.last_run("unconfigured").error


def test_interval_gating_and_force(config, store, now):
    src = FixedSource(config, [make_offer(999_999, raw_id="a", source="fixed")])  # 알림은 없음
    pipeline, _ = build(config, store, [src])
    pipeline.run_once(now)
    again = pipeline.run_once(now + timedelta(minutes=10))
    assert again.skipped_sources == ["fixed"]
    assert pipeline.run_once(now + timedelta(minutes=10), force=True).skipped_sources == []
    # 강제 실행도 실행 기록을 남기므로, 그 시점(+10분)부터 다시 60분을 세야 한다
    assert pipeline.run_once(now + timedelta(minutes=69)).skipped_sources == ["fixed"]
    assert pipeline.run_once(now + timedelta(minutes=71)).skipped_sources == []


def test_quiet_hours_queue_then_flush(config, store, now):
    night = now.replace(hour=3)
    src = FixedSource(config, [make_offer(240_000, raw_id="a", source="fixed")])   # 절대만 → 60 < 90
    pipeline, notifier = build(config, store, [src])
    r = pipeline.run_once(night, force=True)
    assert r.queued == 1 and r.sent == 0 and notifier.sent == []
    morning = pipeline.run_once(now.replace(hour=8), force=True)
    assert morning.sent >= 1 and any("모아둔 알림 1건" in m for m in notifier.sent)
    assert store.queued_alerts() == []


def test_daily_summary_lists_failures_and_stale_sources(config, store, now):
    good = FixedSource(config, [make_offer(999_999, raw_id="a", source="fixed")])
    never = FixedSource(config, [], name="never")
    pipeline, notifier = build(config, store, [BrokenSource(config, {}), good, never])
    pipeline.run_once(now, only="broken", force=True)
    pipeline.run_once(now, only="fixed", force=True)
    text = pipeline.daily_summary(now + timedelta(hours=1), send=True)
    assert "❌ broken" in text and "site changed" in text
    assert "✅ fixed" in text
    assert "never" in text and "실행 기록이 없는" in text
    assert text in notifier.sent


def test_backtest_replays_history_without_sending(config, store, now):
    seq = [(now - timedelta(days=d), 500_000 - d * 0) for d in range(6, 0, -1)]
    store.save_offers([make_offer(p, raw_id="a", source="fixed", fetched_at=t) for t, p in seq])
    store.save_offers([make_offer(240_000, raw_id="a", source="fixed", fetched_at=now)])  # 급락 + 예산 이하
    pipeline, notifier = build(config, store, [])
    found = pipeline.backtest(now - timedelta(days=10), until=now + timedelta(seconds=1))
    assert found and found[-1][0] == now
    assert {"absolute", "drop"} <= set(found[-1][1].rules)
    assert notifier.sent == []


def test_notify_failure_keeps_alert_queued(config, store, now):
    from flight_deal_alert.notify.base import Notifier, NotifyError

    class Failing(Notifier):
        def send(self, text):
            raise NotifyError("down")

    src = FixedSource(config, [make_offer(240_000, raw_id="a", source="fixed")])
    pipeline = Pipeline(config, store, [src], Failing())
    r = pipeline.run_once(now, force=True)
    assert r.sent == 1                      # 시도는 했음
    assert len(store.queued_alerts()) == 1  # 실패해서 보류로 남음

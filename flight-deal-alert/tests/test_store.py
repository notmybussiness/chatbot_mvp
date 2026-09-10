from datetime import timedelta

from conftest import NOW, make_offer


def test_prices_in_window_respects_bounds(store):
    store.save_offers([
        make_offer(100, fetched_at=NOW - timedelta(days=31)),
        make_offer(200, fetched_at=NOW - timedelta(days=29)),
        make_offer(300, fetched_at=NOW),                      # before 경계: 포함 안 됨
    ])
    assert store.prices_in_window("ICN-NRT", NOW - timedelta(days=30), NOW) == [200]


def test_all_time_min_excludes_current_batch(store):
    store.save_offers([make_offer(500, fetched_at=NOW - timedelta(days=1)), make_offer(100, fetched_at=NOW)])
    assert store.all_time_min("ICN-NRT", NOW) == 500
    assert store.all_time_min("ICN-LAX", NOW) is None


def test_last_price_returns_latest_before(store):
    store.save_offers([
        make_offer(500, raw_id="x", fetched_at=NOW - timedelta(days=2)),
        make_offer(400, raw_id="x", fetched_at=NOW - timedelta(days=1)),
        make_offer(300, raw_id="x", fetched_at=NOW),
    ])
    assert store.last_price("test", "x", NOW) == 400
    assert store.last_price("test", "nope", NOW) is None


def test_offers_round_trip_through_sqlite(store):
    original = make_offer(123_456, raw_id="rt", tags=("땡처리", "특가"))
    store.save_offers([original])
    loaded = store.recent_offers("ICN-NRT", NOW - timedelta(days=1))[0]
    assert loaded == original


def test_alert_helpers(store):
    store.save_alert(source="s", raw_id="r", route_key="ICN-NRT", price_krw=100, urgency=50,
                     rules=["absolute"], message="m", status="queued", created_at=NOW)
    assert store.last_alert_price("s", "r") == 100
    assert store.alerts_on_day("ICN-NRT", NOW.date()) == 1
    queued = store.queued_alerts()
    assert len(queued) == 1
    store.mark_sent([queued[0]["id"]], NOW)
    assert store.queued_alerts() == []


def test_source_runs(store):
    from flight_deal_alert.models import SourceRun
    store.record_run(SourceRun("a", NOW, NOW, ok=True, offer_count=3))
    store.record_run(SourceRun("a", NOW + timedelta(hours=1), NOW + timedelta(hours=1), ok=False, error="boom"))
    assert store.last_run("a").error == "boom"
    assert len(store.runs_since(NOW - timedelta(days=1))) == 2
    assert store.last_run("zzz") is None

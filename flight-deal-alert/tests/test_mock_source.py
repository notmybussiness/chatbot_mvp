from datetime import timedelta

from flight_deal_alert.sources.mock import MockSource


def test_mock_is_deterministic_within_a_day(config, now):
    src = MockSource(config, {})
    assert src.fetch(list(config.routes), now) == src.fetch(list(config.routes), now)


def test_mock_changes_across_days(config, now):
    src = MockSource(config, {})
    a = {o.raw_id: o.price_krw for o in src.fetch(list(config.routes), now)}
    b = {o.raw_id: o.price_krw for o in src.fetch(list(config.routes), now + timedelta(days=1))}
    assert a != b


def test_mock_covers_every_route_with_round_trip(config, now):
    offers = MockSource(config, {}).fetch(list(config.routes), now)
    assert {o.route_key for o in offers} == {r.key for r in config.routes}
    assert all(o.return_date is not None for o in offers)  # 두 노선 모두 stay_days 있음
    assert all(o.raw_id and o.url for o in offers)


def test_mock_eventually_emits_tagged_deals(config, now):
    src = MockSource(config, {})
    seen_tags = set()
    for d in range(14):
        for o in src.fetch(list(config.routes), now + timedelta(days=d)):
            seen_tags |= set(o.tags)
    assert "땡처리" in seen_tags

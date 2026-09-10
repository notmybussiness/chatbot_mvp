"""판정 규칙과 스팸 방지 — 이 프로젝트에서 테스트가 가장 많아야 하는 곳."""

from datetime import date, timedelta

from flight_deal_alert.detector import (
    RULE_ABSOLUTE, RULE_ALL_TIME, RULE_DROP, RULE_NEW_TAGGED, RULE_RELATIVE,
)

from conftest import NOW, make_offer, seed_history


# ---- 규칙 1: 절대 기준 ----

def test_absolute_rule_triggers_at_or_below_budget(detector, now):
    deals = detector.evaluate([make_offer(250_000)], now)
    assert deals and RULE_ABSOLUTE in deals[0].rules
    assert "예산" in deals[0].reasons[0]


def test_absolute_rule_does_not_trigger_above_budget(detector, now):
    assert detector.evaluate([make_offer(250_001)], now) == []


def test_unknown_route_has_no_absolute_rule(detector, now):
    assert detector.evaluate([make_offer(1, route="ICN-LAX")], now) == []


# ---- 규칙 2: 상대 기준 ----

def test_relative_rule_requires_enough_observations(detector, store, now):
    # min_observations=5 인데 4건만 있으면 상대 기준은 켜지지 않는다 — 기준선 없는 "싸다" 금지
    seed_history(store, [500_000] * 4)
    rules = [r for d in detector.evaluate([make_offer(300_000)], now) for r in d.rules]
    assert RULE_RELATIVE not in rules              # (역대 최저는 걸릴 수 있음 — 그건 별개 규칙)


def test_relative_rule_triggers_against_median(detector, store, now):
    seed_history(store, [480_000, 500_000, 500_000, 520_000, 600_000])  # 중앙값 500,000
    deals = detector.evaluate([make_offer(400_000)], now)               # 20% 아래
    assert deals and RULE_RELATIVE in deals[0].rules
    assert "20% 저렴" in deals[0].reasons[0]


def test_relative_rule_boundary_just_above_threshold(detector, store, now):
    seed_history(store, [500_000] * 5)
    rules = [r for d in detector.evaluate([make_offer(400_001)], now) for r in d.rules]
    assert RULE_RELATIVE not in rules


def test_relative_rule_ignores_observations_outside_window(detector, store, now):
    # 창 밖(40일 전) 관측만 있으면 기준선이 없는 것과 같다
    seed_history(store, [500_000] * 5, days_ago_start=45)
    rules = [r for d in detector.evaluate([make_offer(300_000)], now) for r in d.rules]
    assert RULE_RELATIVE not in rules


def test_relative_rule_is_per_route(detector, store, now):
    seed_history(store, [500_000] * 5, route="ICN-BKK")
    assert detector.evaluate([make_offer(300_000, route="ICN-NRT")], now) == []


# ---- 규칙 3: 역대 최저 ----

def test_all_time_low_needs_prior_observation(detector, now):
    # 첫 관측은 비교 대상이 없으니 '역대 최저' 가 아니다
    assert detector.evaluate([make_offer(300_000)], now) == []


def test_all_time_low_strictly_below_prior_min(detector, store, now):
    seed_history(store, [400_000, 350_000])
    assert detector.evaluate([make_offer(350_000)], now) == []          # 동률은 최저 갱신이 아님
    deals = detector.evaluate([make_offer(349_000)], now)
    assert deals and RULE_ALL_TIME in deals[0].rules


# ---- 규칙 4: 특가 태그 신규 등장 ----

def test_new_tagged_triggers_on_first_sight(detector, now):
    deals = detector.evaluate([make_offer(300_000, raw_id="deal-1", tags=("땡처리",))], now)
    assert deals and deals[0].rules == [RULE_NEW_TAGGED]


def test_new_tagged_does_not_retrigger_once_seen(detector, store, now):
    first = make_offer(300_000, raw_id="deal-1", tags=("땡처리",), fetched_at=now - timedelta(hours=1))
    store.save_offers([first])
    assert detector.evaluate([make_offer(300_000, raw_id="deal-1", tags=("땡처리",))], now) == []


def test_untagged_new_item_is_not_a_deal(detector, now):
    assert detector.evaluate([make_offer(300_000, raw_id="x", tags=("왕복",))], now) == []


def test_tagged_without_raw_id_cannot_be_tracked(detector, now):
    # raw_id 가 없으면 '처음 봤는지' 를 알 수 없으므로 태그 규칙을 적용하지 않는다
    assert detector.evaluate([make_offer(300_000, tags=("땡처리",))], now) == []


# ---- 규칙 5: 급락 ----

def test_drop_rule_triggers_on_price_fall(detector, store, now):
    store.save_offers([make_offer(400_000, raw_id="item", fetched_at=now - timedelta(days=1))])
    deals = detector.evaluate([make_offer(340_000, raw_id="item")], now)  # 15% 하락
    assert deals and RULE_DROP in deals[0].rules


def test_drop_rule_boundary(detector, store, now):
    store.save_offers([make_offer(400_000, raw_id="item", fetched_at=now - timedelta(days=1))])
    rules = [r for d in detector.evaluate([make_offer(340_001, raw_id="item")], now) for r in d.rules]
    assert RULE_DROP not in rules


def test_drop_compares_with_latest_observation_only(detector, store, now):
    store.save_offers([
        make_offer(400_000, raw_id="item", fetched_at=now - timedelta(days=2)),
        make_offer(300_000, raw_id="item", fetched_at=now - timedelta(days=1)),
    ])
    # 직전(300,000) 대비로는 하락이 아니다 (역대 최저는 별개로 걸린다)
    rules = [r for d in detector.evaluate([make_offer(299_000, raw_id="item")], now) for r in d.rules]
    assert RULE_DROP not in rules and RULE_ALL_TIME in rules


# ---- 긴급도 ----

def test_urgency_absolute_and_relative_is_highest(detector, store, now):
    seed_history(store, [500_000] * 5)
    deal = detector.evaluate([make_offer(240_000)], now)[0]
    assert RULE_ABSOLUTE in deal.rules and RULE_RELATIVE in deal.rules
    assert deal.urgency >= 90


def test_urgency_imminent_tagged_beats_distant(detector, now):
    soon = make_offer(300_000, raw_id="a", tags=("땡처리",), depart=now.date() + timedelta(days=5))
    later = make_offer(300_000, raw_id="b", tags=("땡처리",), depart=now.date() + timedelta(days=60))
    a, b = detector.evaluate([soon, later], now)
    assert a.urgency > b.urgency


def test_urgency_capped_at_100(detector, store, now):
    seed_history(store, [500_000] * 5)
    store.save_offers([make_offer(300_000, raw_id="item", fetched_at=now - timedelta(days=1))])
    deal = detector.evaluate([make_offer(200_000, raw_id="item", tags=("땡처리",))], now)[0]
    assert 0 < deal.urgency <= 100


def test_reasons_are_human_readable_and_one_per_rule(detector, store, now):
    seed_history(store, [500_000] * 5)
    deal = detector.evaluate([make_offer(240_000)], now)[0]
    assert len(deal.reasons) == len(deal.rules)
    assert all(isinstance(r, str) and r for r in deal.reasons)


# ---- 스팸 방지 ----

def test_suppress_when_already_alerted_and_not_cheaper(detector, store, now):
    store.save_alert(source="test", raw_id="item", route_key="ICN-NRT", price_krw=240_000, urgency=60,
                     rules=["absolute"], message="m", status="sent", created_at=now - timedelta(days=3))
    deals = detector.evaluate([make_offer(240_000, raw_id="item")], now)
    kept, dropped = detector.suppress(deals, now)
    assert kept == [] and "더 싸지지 않음" in dropped[0][1]


def test_realert_when_cheaper_than_last_alert(detector, store, now):
    store.save_alert(source="test", raw_id="item", route_key="ICN-NRT", price_krw=240_000, urgency=60,
                     rules=["absolute"], message="m", status="sent", created_at=now - timedelta(days=3))
    deals = detector.evaluate([make_offer(230_000, raw_id="item")], now)
    kept, _ = detector.suppress(deals, now)
    assert len(kept) == 1


def test_daily_cap_per_route(detector, store, now):
    for i in range(2):  # max_alerts_per_route_per_day=2
        store.save_alert(source="test", raw_id=f"old-{i}", route_key="ICN-NRT", price_krw=200_000, urgency=60,
                         rules=["absolute"], message="m", status="sent", created_at=now - timedelta(hours=i + 1))
    deals = detector.evaluate([make_offer(240_000, raw_id="new")], now)
    kept, dropped = detector.suppress(deals, now)
    assert kept == [] and "한도" in dropped[0][1]


def test_daily_cap_counts_within_batch_and_prefers_urgent(detector, store, now):
    seed_history(store, [500_000] * 5)
    offers = [make_offer(p, raw_id=f"r{p}") for p in (240_000, 245_000, 250_000)]  # 셋 다 후보
    kept, dropped = detector.suppress(detector.evaluate(offers, now), now)
    assert len(kept) == 2 and len(dropped) == 1
    assert kept[0].urgency >= kept[1].urgency


def test_daily_cap_is_per_route(detector, store, now):
    offers = [make_offer(240_000, raw_id="n1"), make_offer(240_000, raw_id="n2"),
              make_offer(340_000, route="ICN-BKK", raw_id="b1")]
    kept, _ = detector.suppress(detector.evaluate(offers, now), now)
    assert {d.offer.route_key for d in kept} == {"ICN-NRT", "ICN-BKK"}


def test_batch_duplicates_keep_cheapest(detector, now):
    offers = [make_offer(240_000, raw_id="same"), make_offer(230_000, raw_id="same")]
    kept, _ = detector.suppress(detector.evaluate(offers, now), now)
    assert len(kept) == 1 and kept[0].offer.price_krw == 230_000


def test_cap_does_not_reset_on_next_day_boundary(detector, store, now):
    # 어제 보낸 알림은 오늘 한도에 포함되지 않는다
    store.save_alert(source="test", raw_id="y", route_key="ICN-NRT", price_krw=200_000, urgency=60,
                     rules=["absolute"], message="m", status="sent", created_at=now - timedelta(days=1))
    kept, _ = detector.suppress(detector.evaluate([make_offer(240_000, raw_id="t1"), make_offer(240_000, raw_id="t2")], now), now)
    assert len(kept) == 2


# ---- 조용한 시간대 ----

def test_quiet_hours_window(detector):
    assert detector.is_quiet(NOW.replace(hour=3))
    assert not detector.is_quiet(NOW.replace(hour=7))
    assert not detector.is_quiet(NOW.replace(hour=12))


def test_quiet_hours_wrapping_midnight(detection, store, config):
    from dataclasses import replace
    from flight_deal_alert.detector import DealDetector
    d = DealDetector(replace(detection, quiet_hours=(23, 6)), store, config.budgets)
    assert d.is_quiet(NOW.replace(hour=23)) and d.is_quiet(NOW.replace(hour=2))
    assert not d.is_quiet(NOW.replace(hour=6)) and not d.is_quiet(NOW.replace(hour=12))


def test_should_queue_unless_urgent(detector, store, now):
    seed_history(store, [500_000] * 5)
    urgent = detector.evaluate([make_offer(240_000)], now)[0]            # 절대+상대 → 90+
    mild = detector.evaluate([make_offer(250_000, route="ICN-BKK")], now)[0]  # 절대만 → 60
    night = now.replace(hour=3)
    assert not detector.should_queue(urgent, night)
    assert detector.should_queue(mild, night)
    assert not detector.should_queue(mild, now)

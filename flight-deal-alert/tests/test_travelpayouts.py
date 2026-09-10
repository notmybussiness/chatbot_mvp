import json
from dataclasses import replace

import pytest

from flight_deal_alert.sources.base import NotConfigured
from flight_deal_alert.sources.travelpayouts import TravelpayoutsSource

SAMPLE = {
    "success": True, "currency": "krw",
    "data": [
        {"origin": "ICN", "destination": "NRT", "departure_at": "2026-12-20T09:00:00+09:00", "return_at": "2026-12-25T18:00:00+09:00",
         "price": 213000, "airline": "7C", "flight_number": "1102", "transfers": 0, "link": "/search/ICN2012NRT25121"},
        {"origin": "ICN", "destination": "NRT", "departure_at": "2027-05-01", "price": 150000, "airline": "KE", "transfers": 0, "link": ""},
    ],
}


def test_parse_filters_to_window_and_normalises(config, now):
    src = TravelpayoutsSource(replace(config, secrets={"TRAVELPAYOUTS_TOKEN": "t"}), {"request_delay_sec": 0})
    offers = src._parse(json.dumps(SAMPLE), config.routes[0], now)
    assert len(offers) == 1                       # 2027-05-01 은 창 밖
    o = offers[0]
    assert o.price_krw == 213000 and o.airline == "7C" and o.stops == 0
    assert o.depart_date.isoformat() == "2026-12-20" and o.return_date.isoformat() == "2026-12-25"
    assert o.url.startswith("https://www.aviasales.com/")
    assert o.raw_id


def test_months_cover_departure_window(config):
    src = TravelpayoutsSource(config, {})
    assert src._months(config.routes[0]) == ["2026-11", "2026-12", "2027-01", "2027-02"]


def test_requires_token(config, now):
    src = TravelpayoutsSource(config, {})
    with pytest.raises(NotConfigured):
        src.fetch(list(config.routes), now)
    assert "TOKEN" in src.diagnose(config.routes[0], now).error

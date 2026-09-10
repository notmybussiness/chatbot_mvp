import json
from datetime import date

import pytest

from flight_deal_alert.sources.base import SourceError
from flight_deal_alert.sources.naver import NaverSource, OfferExtractor


@pytest.fixture
def naver(config):
    return NaverSource(config, {"request_delay_sec": 0})


# ---- 요청 템플릿 ----

def test_one_way_request_from_default_template(naver, config):
    body = json.loads(naver.render_request(config.routes[0], date(2026, 12, 20), None))
    assert body["tripType"] == "OW" and body["adult"] == 1 and body["fareType"] == "Y"
    assert len(body["fly"]) == 1 and body["fly"][0]["departureDate"] == "20261220"
    assert not any(k.startswith("_") for k in body)          # 설명용 키는 제거


def test_round_trip_appends_return_leg(naver, config):
    body = json.loads(naver.render_request(config.routes[0], date(2026, 12, 20), date(2026, 12, 27)))
    assert body["tripType"] == "RT" and len(body["fly"]) == 2
    assert body["fly"][1] == {"departureAirport": "NRT", "arrivalAirport": "ICN", "departureDate": "20261227"}


def test_custom_template_is_used_as_is(config, tmp_path):
    tpl = tmp_path / "t.json"
    tpl.write_text('{"k": "{{origin}}_{{destination}}_{{departureDateDash}}", "n": {{adults}}}', encoding="utf-8")
    src = NaverSource(config, {"request_template": str(tpl)})
    body = json.loads(src.render_request(config.routes[0], date(2026, 12, 20), None))
    assert body == {"k": "ICN_NRT_2026-12-20", "n": 1}


def test_unknown_placeholder_fails_fast(config, tmp_path):
    tpl = tmp_path / "t.json"
    tpl.write_text('{"k": "{{nope}}"}', encoding="utf-8")
    with pytest.raises(SourceError, match="nope"):
        NaverSource(config, {"request_template": str(tpl)}).render_request(config.routes[0], date(2026, 12, 20), None)


def test_invalid_json_template_fails_fast(config, tmp_path):
    tpl = tmp_path / "t.json"
    tpl.write_text('{"broken": ', encoding="utf-8")
    with pytest.raises(SourceError, match="JSON"):
        NaverSource(config, {"request_template": str(tpl)}).render_request(config.routes[0], date(2026, 12, 20), None)


# ---- 응답 추출 ----

@pytest.fixture
def extractor():
    return OfferExtractor(
        price_keys=["totalFare", "price"], airline_keys=["airlineName"], stops_keys=["stopCount"],
        link_keys=["deepLink"], min_price=10_000,
    )


def test_extracts_nested_fare_inheriting_airline(extractor):
    found, keys = extractor.extract({
        "result": {"itineraries": [
            {"airlineName": "아시아나항공", "stopCount": 1, "deepLink": "https://x/1",
             "fares": {"A": {"totalFare": 388000}, "B": {"totalFare": "412,000"}}},
        ]}
    })
    assert sorted(found) == [(388000, "아시아나항공", 1, "https://x/1"), (412000, "아시아나항공", 1, "https://x/1")]
    assert keys == ["totalFare"]


def test_extract_ignores_small_numbers_and_reports_no_keys(extractor):
    found, keys = extractor.extract({"price": 8500, "status": "OK"})
    assert found == [] and keys == []


def test_extract_text_handles_sse_chunks_and_dedupes(extractor):
    raw = 'data: {"airlineName":"대한항공","totalFare":452300}\n\ndata: {"airlineName":"대한항공","totalFare":452300}\n\ndata: [DONE]\n'
    found, keys = extractor.extract_text(raw)
    assert found == [(452300, "대한항공", 0, None)] and keys == ["totalFare"]


def test_extract_text_plain_json_sorted_by_price(extractor):
    raw = json.dumps({"flights": [{"airlineName": "A", "price": 500000}, {"airlineName": "B", "price": 300000}]})
    found, _ = extractor.extract_text(raw)
    assert [f[0] for f in found] == [300000, 500000]


def test_naver_is_marked_unverified(naver):
    assert naver.verified is False

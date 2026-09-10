from flight_deal_alert.sources.base import SourceError
from flight_deal_alert.sources.html_list import HtmlListSource

from conftest import FIXTURES

OPTIONS = {
    "url": "https://example.invalid/list",
    "tags": ["공동구매"],
    "item_selector": "ul.list > li",
    "fields": {
        "title": {"selector": ".title"},
        "price": {"selector": ".price", "regex": "[0-9,]+"},
        "url": {"selector": "a", "attr": "href"},
        "depart": {"selector": ".date", "regex": r"\d{4}[-.]\d{2}[-.]\d{2}"},
    },
    "request_delay_sec": 0,
}


def test_parses_items_and_maps_by_alias(config, now):
    src = HtmlListSource("modetour", config, OPTIONS)
    offers, notes = src.parse((FIXTURES / "deal_list.html").read_text(encoding="utf-8"), list(config.routes), now)

    by_dest = {o.destination: o for o in offers}
    assert set(by_dest) == {"NRT", "BKK"}        # 오사카·파리는 관심 노선이 아니라 버려짐
    tokyo = by_dest["NRT"]
    assert tokyo.price_krw == 189_000
    assert tokyo.depart_date.isoformat() == "2026-12-20"
    assert tokyo.url == "https://example.invalid/deal/101"     # 상대 링크 → 절대
    assert tokyo.tags == ("공동구매", "땡처리", "특가")            # 고정 태그 + 제목에서 발견한 태그들
    assert tokyo.raw_id and tokyo.raw_id != by_dest["BKK"].raw_id
    assert any("4개 걸림" in n for n in notes)
    assert any("미매핑 1개" in n for n in notes)
    assert any("가격 파싱 실패 1개" in n for n in notes)


def test_zero_matches_gives_actionable_note(config, now):
    src = HtmlListSource("modetour", config, OPTIONS)
    offers, notes = src.parse("<html><body><p>nothing</p></body></html>", list(config.routes), now)
    assert offers == []
    assert any("0개" in n for n in notes)


def test_missing_required_options_fails_fast(config):
    import pytest
    with pytest.raises(SourceError):
        HtmlListSource("x", config, {"url": "https://a"})


def test_is_marked_unverified(config):
    assert HtmlListSource("modetour", config, OPTIONS).verified is False

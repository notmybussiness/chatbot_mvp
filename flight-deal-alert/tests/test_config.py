from pathlib import Path

import pytest

from flight_deal_alert.config import ConfigError, load_config

EXAMPLE = Path(__file__).parent.parent / "config.example.yaml"


def test_example_config_loads(tmp_path):
    cfg = load_config(EXAMPLE, env_path=tmp_path / ".env")
    assert [r.key for r in cfg.routes] == ["ICN-NRT", "ICN-BKK", "ICN-CDG"]
    assert cfg.budgets["ICN-NRT"] == 250_000
    assert cfg.routes[0].stay_days == (3, 7)
    assert "도쿄" in cfg.destination_aliases["NRT"]
    assert cfg.sources["mock"]["enabled"] is True
    assert cfg.notify.channel == "stdout"


def _write(tmp_path, body: str) -> Path:
    p = tmp_path / "config.yaml"
    p.write_text(body, encoding="utf-8")
    return p


def test_missing_budget_is_an_error(tmp_path):
    with pytest.raises(ConfigError, match="budget_krw"):
        load_config(_write(tmp_path, "routes:\n  - destination: NRT\n"))


def test_bad_date_range_is_an_error(tmp_path):
    with pytest.raises(ConfigError, match="depart_to"):
        load_config(_write(tmp_path, "routes:\n  - {destination: NRT, budget_krw: 1, depart_from: 2026-12-01, depart_to: 2026-11-01}\n"))


def test_telegram_requires_secrets(tmp_path):
    body = "routes:\n  - {destination: NRT, budget_krw: 1}\nnotify: {channel: telegram}\n"
    with pytest.raises(ConfigError, match="TELEGRAM"):
        load_config(_write(tmp_path, body), env_path=tmp_path / ".env")


def test_env_file_and_environment_are_merged(tmp_path, monkeypatch):
    (tmp_path / ".env").write_text("TELEGRAM_BOT_TOKEN=file\nTELEGRAM_CHAT_ID='123'\n", encoding="utf-8")
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "env-wins")
    body = "routes:\n  - {destination: NRT, budget_krw: 1}\nnotify: {channel: telegram}\n"
    cfg = load_config(_write(tmp_path, body), env_path=tmp_path / ".env")
    assert cfg.secrets["TELEGRAM_BOT_TOKEN"] == "env-wins" and cfg.secrets["TELEGRAM_CHAT_ID"] == "123"

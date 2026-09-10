"""설정 로딩. 동작 설정은 YAML 하나, 시크릿은 .env — 코드에 키를 넣지 않는다."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any

import yaml


class ConfigError(ValueError):
    pass


@dataclass(frozen=True)
class RouteConfig:
    origin: str
    destination: str
    budget_krw: int
    depart_from: date
    depart_to: date
    stay_days: tuple[int, int] | None = None
    adults: int = 1
    cabin: str = "economy"

    @property
    def key(self) -> str:
        return f"{self.origin}-{self.destination}"

    @property
    def is_round_trip(self) -> bool:
        return self.stay_days is not None


@dataclass(frozen=True)
class DetectionConfig:
    relative_window_days: int = 30
    relative_discount_pct: float = 20.0
    relative_min_observations: int = 10
    drop_pct: float = 15.0
    deal_tags: tuple[str, ...] = ("땡처리", "공동구매", "특가")
    imminent_days: int = 14
    max_alerts_per_route_per_day: int = 5
    quiet_hours: tuple[int, int] | None = (0, 7)
    quiet_bypass_urgency: int = 90


@dataclass(frozen=True)
class NotifyConfig:
    channel: str = "stdout"
    daily_summary_hour: int = 8
    timezone: str = "Asia/Seoul"


@dataclass(frozen=True)
class AppConfig:
    routes: tuple[RouteConfig, ...]
    detection: DetectionConfig
    notify: NotifyConfig
    sqlite_path: Path
    default_origin: str = "ICN"
    destination_aliases: dict[str, tuple[str, ...]] = field(default_factory=dict)
    sources: dict[str, dict[str, Any]] = field(default_factory=dict)
    secrets: dict[str, str] = field(default_factory=dict)

    @property
    def budgets(self) -> dict[str, int]:
        return {r.key: r.budget_krw for r in self.routes}

    def route(self, key: str) -> RouteConfig | None:
        return next((r for r in self.routes if r.key == key), None)


def _as_date(value: Any, label: str) -> date:
    if isinstance(value, date):
        return value
    try:
        return date.fromisoformat(str(value))
    except ValueError as exc:
        raise ConfigError(f"{label}: 날짜 형식이 잘못되었습니다 ({value!r}). YYYY-MM-DD 로 적어주세요") from exc


def _parse_route(raw: dict[str, Any], default_origin: str) -> RouteConfig:
    if "destination" not in raw:
        raise ConfigError("routes 항목에 destination 이 없습니다")
    if "budget_krw" not in raw:
        raise ConfigError(f"routes[{raw.get('destination')}]: budget_krw(절대 기준)가 없습니다")

    stay = raw.get("stay_days")
    stay_days = None
    if stay:
        if len(stay) != 2 or stay[0] > stay[1]:
            raise ConfigError(f"routes[{raw['destination']}]: stay_days 는 [최소, 최대] 형태여야 합니다")
        stay_days = (int(stay[0]), int(stay[1]))

    depart_from = _as_date(raw.get("depart_from", date.today()), "depart_from")
    depart_to = _as_date(raw.get("depart_to", depart_from), "depart_to")
    if depart_to < depart_from:
        raise ConfigError(f"routes[{raw['destination']}]: depart_to 가 depart_from 보다 빠릅니다")

    return RouteConfig(
        origin=str(raw.get("origin", default_origin)).upper(),
        destination=str(raw["destination"]).upper(),
        budget_krw=int(raw["budget_krw"]),
        depart_from=depart_from,
        depart_to=depart_to,
        stay_days=stay_days,
        adults=int(raw.get("adults", 1)),
        cabin=str(raw.get("cabin", "economy")),
    )


def _parse_detection(raw: dict[str, Any]) -> DetectionConfig:
    quiet = raw.get("quiet_hours")
    quiet_hours = tuple(int(h) for h in quiet) if quiet else None
    if quiet_hours and (len(quiet_hours) != 2 or not all(0 <= h <= 24 for h in quiet_hours)):
        raise ConfigError("detection.quiet_hours 는 [시작시, 종료시] (0~24) 형태여야 합니다")

    return DetectionConfig(
        relative_window_days=int(raw.get("relative_window_days", 30)),
        relative_discount_pct=float(raw.get("relative_discount_pct", 20)),
        relative_min_observations=int(raw.get("relative_min_observations", 10)),
        drop_pct=float(raw.get("drop_pct", 15)),
        deal_tags=tuple(raw.get("deal_tags", ("땡처리", "공동구매", "특가"))),
        imminent_days=int(raw.get("imminent_days", 14)),
        max_alerts_per_route_per_day=int(raw.get("max_alerts_per_route_per_day", 5)),
        quiet_hours=quiet_hours,
        quiet_bypass_urgency=int(raw.get("quiet_bypass_urgency", 90)),
    )


def load_env(path: Path | None) -> dict[str, str]:
    """`.env` 를 읽어 환경변수와 합친다. 실제 환경변수가 파일보다 우선한다."""
    values: dict[str, str] = {}
    if path and path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip().strip("'\"")
    for key in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "TRAVELPAYOUTS_TOKEN"):
        if os.environ.get(key):
            values[key] = os.environ[key]
    return values


def load_config(config_path: Path, env_path: Path | None = None) -> AppConfig:
    if not config_path.exists():
        raise ConfigError(
            f"설정 파일이 없습니다: {config_path}. config.example.yaml 을 복사해 config.yaml 로 저장하세요"
        )
    raw = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}

    default_origin = str(raw.get("default_origin", "ICN")).upper()
    routes_raw = raw.get("routes") or []
    if not routes_raw:
        raise ConfigError("routes 가 비어 있습니다. 관심 노선을 하나 이상 적어주세요")
    routes = tuple(_parse_route(r, default_origin) for r in routes_raw)

    aliases = {
        str(code).upper(): tuple(str(a) for a in names)
        for code, names in (raw.get("destination_aliases") or {}).items()
    }

    notify_raw = raw.get("notify") or {}
    notify = NotifyConfig(
        channel=str(notify_raw.get("channel", "stdout")),
        daily_summary_hour=int(notify_raw.get("daily_summary_hour", 8)),
        timezone=str(notify_raw.get("timezone", "Asia/Seoul")),
    )

    storage = raw.get("storage") or {}
    sqlite_path = Path(storage.get("sqlite_path", "data/flight_deals.db"))

    secrets = load_env(env_path if env_path is not None else config_path.parent / ".env")

    if notify.channel == "telegram" and not (secrets.get("TELEGRAM_BOT_TOKEN") and secrets.get("TELEGRAM_CHAT_ID")):
        raise ConfigError("notify.channel 이 telegram 인데 TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID 가 .env 에 없습니다")

    return AppConfig(
        routes=routes,
        detection=_parse_detection(raw.get("detection") or {}),
        notify=notify,
        sqlite_path=sqlite_path,
        default_origin=default_origin,
        destination_aliases=aliases,
        sources=raw.get("sources") or {},
        secrets=secrets,
    )

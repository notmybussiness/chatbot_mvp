"""SQLite 저장소. 판정과 알림은 여기 있는 데이터만 읽는다 — 외부 호출 없이 재실행·백테스트가 가능해야 한다."""

from __future__ import annotations

import sqlite3
from datetime import date, datetime, timedelta
from pathlib import Path

from .models import Offer, SourceRun

_SCHEMA = """
CREATE TABLE IF NOT EXISTS offers (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    source       TEXT NOT NULL,
    origin       TEXT NOT NULL,
    destination  TEXT NOT NULL,
    route_key    TEXT NOT NULL,
    depart_date  TEXT NOT NULL,
    return_date  TEXT,
    price_krw    INTEGER NOT NULL,
    currency     TEXT NOT NULL,
    airline      TEXT NOT NULL,
    stops        INTEGER NOT NULL,
    url          TEXT NOT NULL,
    raw_id       TEXT NOT NULL,
    tags         TEXT NOT NULL,
    fetched_at   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_offers_route_time ON offers(route_key, fetched_at);
CREATE INDEX IF NOT EXISTS idx_offers_source_raw ON offers(source, raw_id, fetched_at);

CREATE TABLE IF NOT EXISTS alerts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    source       TEXT NOT NULL,
    raw_id       TEXT NOT NULL,
    route_key    TEXT NOT NULL,
    price_krw    INTEGER NOT NULL,
    urgency      INTEGER NOT NULL,
    rules        TEXT NOT NULL,
    message      TEXT NOT NULL,
    status       TEXT NOT NULL,          -- sent | queued
    created_at   TEXT NOT NULL,
    sent_at      TEXT
);
CREATE INDEX IF NOT EXISTS idx_alerts_route_day ON alerts(route_key, created_at);
CREATE INDEX IF NOT EXISTS idx_alerts_source_raw ON alerts(source, raw_id);

CREATE TABLE IF NOT EXISTS source_runs (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    source       TEXT NOT NULL,
    started_at   TEXT NOT NULL,
    finished_at  TEXT NOT NULL,
    ok           INTEGER NOT NULL,
    offer_count  INTEGER NOT NULL,
    error        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_runs_source_time ON source_runs(source, started_at);
"""


def _iso(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")


class Store:
    def __init__(self, path: Path | str):
        self.path = Path(path)
        if str(self.path) != ":memory:":
            self.path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(self.path))
        self._conn.row_factory = sqlite3.Row
        self._conn.executescript(_SCHEMA)

    def close(self) -> None:
        self._conn.close()

    # ---- offers ----

    def save_offers(self, offers: list[Offer]) -> int:
        rows = [
            (
                o.source, o.origin, o.destination, o.route_key,
                o.depart_date.isoformat(), o.return_date.isoformat() if o.return_date else None,
                o.price_krw, o.currency, o.airline, o.stops, o.url, o.raw_id,
                ",".join(o.tags), _iso(o.fetched_at),
            )
            for o in offers
        ]
        with self._conn:
            self._conn.executemany(
                "INSERT INTO offers (source, origin, destination, route_key, depart_date, return_date, "
                "price_krw, currency, airline, stops, url, raw_id, tags, fetched_at) "
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                rows,
            )
        return len(rows)

    def prices_in_window(self, route_key: str, since: datetime, before: datetime) -> list[int]:
        """[since, before) 구간에 관측된 가격. 상대 기준의 기준선."""
        cur = self._conn.execute(
            "SELECT price_krw FROM offers WHERE route_key = ? AND fetched_at >= ? AND fetched_at < ?",
            (route_key, _iso(since), _iso(before)),
        )
        return [r[0] for r in cur]

    def all_time_min(self, route_key: str, before: datetime) -> int | None:
        cur = self._conn.execute(
            "SELECT MIN(price_krw) FROM offers WHERE route_key = ? AND fetched_at < ?",
            (route_key, _iso(before)),
        )
        return cur.fetchone()[0]

    def last_price(self, source: str, raw_id: str, before: datetime) -> int | None:
        """같은 항목(raw_id)의 직전 관측 가격. 급락·신규 등장 판정에 쓴다."""
        cur = self._conn.execute(
            "SELECT price_krw FROM offers WHERE source = ? AND raw_id = ? AND fetched_at < ? "
            "ORDER BY fetched_at DESC LIMIT 1",
            (source, raw_id, _iso(before)),
        )
        row = cur.fetchone()
        return row[0] if row else None

    def offer_count_since(self, since: datetime) -> int:
        cur = self._conn.execute("SELECT COUNT(*) FROM offers WHERE fetched_at >= ?", (_iso(since),))
        return cur.fetchone()[0]

    def recent_offers(self, route_key: str, since: datetime) -> list[Offer]:
        cur = self._conn.execute(
            "SELECT * FROM offers WHERE route_key = ? AND fetched_at >= ? ORDER BY fetched_at ASC",
            (route_key, _iso(since)),
        )
        return [self._row_to_offer(r) for r in cur]

    def offers_between(self, since: datetime, until: datetime) -> list[Offer]:
        cur = self._conn.execute(
            "SELECT * FROM offers WHERE fetched_at >= ? AND fetched_at < ? ORDER BY fetched_at ASC",
            (_iso(since), _iso(until)),
        )
        return [self._row_to_offer(r) for r in cur]

    @staticmethod
    def _row_to_offer(r: sqlite3.Row) -> Offer:
        return Offer(
            source=r["source"], origin=r["origin"], destination=r["destination"],
            depart_date=date.fromisoformat(r["depart_date"]),
            return_date=date.fromisoformat(r["return_date"]) if r["return_date"] else None,
            price_krw=r["price_krw"], currency=r["currency"], airline=r["airline"], stops=r["stops"],
            url=r["url"], raw_id=r["raw_id"],
            tags=tuple(t for t in r["tags"].split(",") if t),
            fetched_at=datetime.fromisoformat(r["fetched_at"]),
        )

    # ---- alerts ----

    def last_alert_price(self, source: str, raw_id: str) -> int | None:
        cur = self._conn.execute(
            "SELECT price_krw FROM alerts WHERE source = ? AND raw_id = ? ORDER BY created_at DESC LIMIT 1",
            (source, raw_id),
        )
        row = cur.fetchone()
        return row[0] if row else None

    def alerts_on_day(self, route_key: str, day: date) -> int:
        start = datetime.combine(day, datetime.min.time())
        end = start + timedelta(days=1)
        cur = self._conn.execute(
            "SELECT COUNT(*) FROM alerts WHERE route_key = ? AND created_at >= ? AND created_at < ?",
            (route_key, _iso(start), _iso(end)),
        )
        return cur.fetchone()[0]

    def save_alert(
        self, *, source: str, raw_id: str, route_key: str, price_krw: int, urgency: int,
        rules: list[str], message: str, status: str, created_at: datetime,
    ) -> int:
        with self._conn:
            cur = self._conn.execute(
                "INSERT INTO alerts (source, raw_id, route_key, price_krw, urgency, rules, message, status, "
                "created_at, sent_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                (
                    source, raw_id, route_key, price_krw, urgency, ",".join(rules), message, status,
                    _iso(created_at), _iso(created_at) if status == "sent" else None,
                ),
            )
        return int(cur.lastrowid)

    def queued_alerts(self) -> list[sqlite3.Row]:
        return list(self._conn.execute(
            "SELECT * FROM alerts WHERE status = 'queued' ORDER BY urgency DESC, created_at ASC"
        ))

    def mark_sent(self, alert_ids: list[int], sent_at: datetime) -> None:
        with self._conn:
            self._conn.executemany(
                "UPDATE alerts SET status = 'sent', sent_at = ? WHERE id = ?",
                [(_iso(sent_at), i) for i in alert_ids],
            )

    def alert_count_since(self, since: datetime) -> int:
        cur = self._conn.execute("SELECT COUNT(*) FROM alerts WHERE created_at >= ?", (_iso(since),))
        return cur.fetchone()[0]

    # ---- source runs ----

    def record_run(self, run: SourceRun) -> None:
        with self._conn:
            self._conn.execute(
                "INSERT INTO source_runs (source, started_at, finished_at, ok, offer_count, error) "
                "VALUES (?,?,?,?,?,?)",
                (run.source, _iso(run.started_at), _iso(run.finished_at), int(run.ok), run.offer_count, run.error),
            )

    def runs_since(self, since: datetime) -> list[SourceRun]:
        cur = self._conn.execute(
            "SELECT * FROM source_runs WHERE started_at >= ? ORDER BY started_at ASC", (_iso(since),)
        )
        return [
            SourceRun(
                source=r["source"], started_at=datetime.fromisoformat(r["started_at"]),
                finished_at=datetime.fromisoformat(r["finished_at"]), ok=bool(r["ok"]),
                offer_count=r["offer_count"], error=r["error"],
            )
            for r in cur
        ]

    def last_run(self, source: str) -> SourceRun | None:
        cur = self._conn.execute(
            "SELECT * FROM source_runs WHERE source = ? ORDER BY started_at DESC LIMIT 1", (source,)
        )
        r = cur.fetchone()
        if not r:
            return None
        return SourceRun(
            source=r["source"], started_at=datetime.fromisoformat(r["started_at"]),
            finished_at=datetime.fromisoformat(r["finished_at"]), ok=bool(r["ok"]),
            offer_count=r["offer_count"], error=r["error"],
        )

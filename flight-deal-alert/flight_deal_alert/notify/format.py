"""알림 메시지 본문. 필수 항목: 긴급도, 노선, 날짜, 가격, 왜 특가인지, 항공사/경유, 소스, 링크."""

from __future__ import annotations

from datetime import datetime

from ..models import Deal, SourceRun


def _won(value: int) -> str:
    return f"{value:,}원"


def _urgency_mark(urgency: int) -> str:
    if urgency >= 90:
        return "🔥🔥🔥"
    if urgency >= 75:
        return "🔥🔥"
    if urgency >= 60:
        return "🔥"
    return "•"


def format_deal(deal: Deal) -> str:
    o = deal.offer
    dates = f"{o.depart_date}"
    if o.return_date:
        dates += f" ~ {o.return_date}"
    stops = "직항" if o.stops == 0 else f"경유 {o.stops}회"
    tags = f" [{'/'.join(o.tags)}]" if o.tags else ""

    lines = [
        f"{_urgency_mark(deal.urgency)} 긴급도 {deal.urgency} · {o.origin}→{o.destination}{tags}",
        f"💰 {_won(o.price_krw)}",
        f"📅 {dates}",
        f"✈️ {o.airline or '항공사 미상'} · {stops}",
        "",
        "왜 특가인가:",
        *[f"  - {r}" for r in deal.reasons],
        "",
        f"출처: {o.source}",
    ]
    if o.url:
        lines.append(o.url)
    return "\n".join(lines)


def format_digest(deals: list[Deal], title: str) -> str:
    """조용한 시간대에 모아둔 알림을 한 번에."""
    lines = [f"📬 {title} — {len(deals)}건", ""]
    for deal in sorted(deals, key=lambda d: -d.urgency):
        o = deal.offer
        lines.append(f"{_urgency_mark(deal.urgency)} {o.origin}→{o.destination} {_won(o.price_krw)} ({o.depart_date}) · {deal.reasons[0]}")
        if o.url:
            lines.append(f"   {o.url}")
    return "\n".join(lines)


def format_summary(
    *, now: datetime, offer_count: int, alert_count: int, runs: list[SourceRun], stale_sources: list[str]
) -> str:
    """하루 1회 요약. 소스가 조용히 죽어 있는 걸 여기서 알아채야 한다."""
    lines = [f"📊 {now.date()} 항공권 특가 요약", f"관측 {offer_count}건 · 알림 {alert_count}건", ""]

    by_source: dict[str, list[SourceRun]] = {}
    for run in runs:
        by_source.setdefault(run.source, []).append(run)

    if by_source:
        lines.append("소스 상태:")
        for source, source_runs in sorted(by_source.items()):
            ok = sum(1 for r in source_runs if r.ok)
            fail = len(source_runs) - ok
            offers = sum(r.offer_count for r in source_runs)
            mark = "✅" if fail == 0 else ("⚠️" if ok else "❌")
            line = f"  {mark} {source}: 성공 {ok} / 실패 {fail} · {offers}건"
            last_err = next((r.error for r in reversed(source_runs) if not r.ok and r.error), "")
            if last_err:
                line += f"\n     └ {last_err[:120]}"
            lines.append(line)
    else:
        lines.append("소스 실행 기록 없음")

    if stale_sources:
        lines += ["", "⛔ 켜져 있는데 실행 기록이 없는 소스: " + ", ".join(stale_sources)]
    return "\n".join(lines)

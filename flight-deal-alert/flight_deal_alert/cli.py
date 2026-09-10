"""명령줄. run / daemon / diagnose / backtest / report"""

from __future__ import annotations

import argparse
import logging
import sys
from datetime import datetime, timedelta
from pathlib import Path

from .config import ConfigError, load_config
from .notify import build_notifier
from .notify.format import format_deal
from .pipeline import Pipeline
from .scheduler import Daemon
from .sources import KNOWN_SOURCES, build_sources
from .store import Store


def _build(args) -> tuple[Pipeline, Store]:
    config = load_config(Path(args.config), Path(args.env) if args.env else None)
    store = Store(config.sqlite_path)
    sources = build_sources(config, include_disabled=getattr(args, "include_disabled", False))
    notifier = build_notifier(config.notify.channel, config.secrets, dry_run=getattr(args, "dry_run", False))
    return Pipeline(config, store, sources, notifier, source_gap_sec=2.0), store


def cmd_run(args) -> int:
    pipeline, store = _build(args)
    try:
        result = pipeline.run_once(only=args.source, force=args.force)
    finally:
        store.close()
    print(
        f"관측 {result.offers}건 · 후보 {result.deals}건 · 전송 {result.sent}건 · 보류 {result.queued}건 · 억제 {result.suppressed}건"
    )
    if result.skipped_sources:
        print(f"주기 미도래로 건너뜀: {', '.join(result.skipped_sources)} (--force 로 강제 실행)")
    if result.failed_sources:
        print(f"실패한 소스: {', '.join(result.failed_sources)} — `diagnose <source>` 로 확인하세요")
    return 0


def cmd_daemon(args) -> int:
    pipeline, store = _build(args)
    try:
        Daemon(pipeline, summary_hour=pipeline.config.notify.daily_summary_hour, tick_seconds=args.tick).run_forever()
    except KeyboardInterrupt:
        print("종료")
    finally:
        store.close()
    return 0


def cmd_diagnose(args) -> int:
    args.include_disabled = True
    pipeline, store = _build(args)
    try:
        source = next((s for s in pipeline.sources if s.name == args.source), None)
        if source is None:
            print(f"소스를 찾을 수 없습니다: {args.source}. config.yaml 의 sources 에 있어야 합니다 (알려진 값: {', '.join(KNOWN_SOURCES)})")
            return 2
        route = pipeline.config.route(args.route) if args.route else pipeline.config.routes[0]
        if route is None:
            print(f"노선을 찾을 수 없습니다: {args.route}")
            return 2

        diag = source.diagnose(route, datetime.now())
        print(f"# 소스: {diag.source}  노선: {route.key}")
        for note in diag.notes:
            print(f"! {note}")
        if diag.request:
            print("\n## 보낸 요청\n" + diag.request)
        if diag.error:
            print("\n## 오류\n" + diag.error)
        if diag.response_head:
            print(f"\n## 받은 응답 (앞 {len(diag.response_head)}자 / 전체 {diag.response_bytes}바이트)\n" + diag.response_head)
        print(f"\n## 정규화된 항공권 {len(diag.offers)}건")
        for o in diag.offers[:10]:
            print(f"  {o.price_krw:>9,}원  {o.depart_date}  {o.airline or '-':<10} {o.url[:70]}")
        return 0 if not diag.error else 1
    finally:
        store.close()


def cmd_backtest(args) -> int:
    args.dry_run = True
    pipeline, store = _build(args)
    try:
        since = datetime.now() - timedelta(days=args.days)
        found = pipeline.backtest(since)
        print(f"최근 {args.days}일 저장 데이터로 판정 재실행: {len(found)}건 후보 (알림은 보내지 않음)\n")
        for ts, deal in found:
            print(f"[{ts:%Y-%m-%d %H:%M}]")
            print(format_deal(deal))
            print()
        return 0
    finally:
        store.close()


def cmd_report(args) -> int:
    pipeline, store = _build(args)
    try:
        print(pipeline.daily_summary(send=not args.dry_run))
        return 0
    finally:
        store.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="flight-deal-alert", description="항공권 특가 알림")
    parser.add_argument("--config", default="config.yaml")
    parser.add_argument("--env", default=None, help=".env 경로 (기본: config 옆)")
    parser.add_argument("-v", "--verbose", action="store_true")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("run", help="1회 실행")
    p.add_argument("--dry-run", action="store_true", help="알림 대신 화면 출력")
    p.add_argument("--source", default=None, help="특정 소스만")
    p.add_argument("--force", action="store_true", help="주기 무시하고 실행")
    p.set_defaults(func=cmd_run)

    p = sub.add_parser("daemon", help="스케줄 상주")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--tick", type=int, default=60, help="깨어나는 간격(초)")
    p.set_defaults(func=cmd_daemon)

    p = sub.add_parser("diagnose", help="소스 연동 점검 — 실제 요청/응답 원본 출력")
    p.add_argument("source")
    p.add_argument("--route", default=None, help="예: ICN-NRT (기본: 첫 노선)")
    p.set_defaults(func=cmd_diagnose)

    p = sub.add_parser("backtest", help="저장 데이터로 판정만 재실행")
    p.add_argument("--days", type=int, default=30)
    p.set_defaults(func=cmd_backtest)

    p = sub.add_parser("report", help="요약 리포트")
    p.add_argument("--dry-run", action="store_true", help="보내지 않고 출력만")
    p.set_defaults(func=cmd_report)

    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    try:
        return args.func(args)
    except ConfigError as exc:
        print(f"설정 오류: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

# =====================================================================================
# STS 분석 이벤트 보존기간 정리 (#373 — 보존 90일, 삭제 주체: 운영자 월 1회 수동 실행).
#
# 사용법:
#   uv run --no-sync python -m scripts.prune_sts_events           # dry-run: 삭제 대상 건수만 출력
#   uv run --no-sync python -m scripts.prune_sts_events --apply   # 실제 삭제
#
# 정책 (단일 원천: docs/sts_overlay_metrics.md):
#   - 발화율 관측·컷 재조정 근거는 최근 12주 추이면 충분하다 → 보존 90일.
#   - 90일 경과분은 사용자 단위 지표에 더는 쓰이지 않으므로 일괄 삭제한다(개인 단위 원시
#     이벤트를 필요 이상 보관하지 않는다 — 최소 보관).
#   - 탈퇴 사용자 이벤트는 이 스크립트와 무관하게 탈퇴 시점에 FK 그래프 파기(#356)로 즉시 삭제된다.
#
# 기본은 dry-run 이라 운영 DB 에 안전하다. --apply 없이는 DELETE 를 실행하지 않는다.
# =====================================================================================
from __future__ import annotations

import asyncio
import sys

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncConnection

from app.core.db.session import engine

RETENTION_DAYS = 90

# sts_score_view_events 는 분모 폐기(#429, 마이그레이션 0024)로 목록에서 제거했다.
EVENT_TABLES = ("sts_overlay_events",)


async def prune(conn: AsyncConnection, *, apply: bool, retention_days: int = RETENTION_DAYS) -> dict[str, int]:
    """보존기간 경과 이벤트를 테이블별로 집계(dry-run)하거나 삭제한다. 테스트 진입점."""
    counted: dict[str, int] = {}
    for table in EVENT_TABLES:
        # 테이블명은 위 상수 목록에서만 온다(외부 입력 아님) — f-string 바인딩 안전.
        count = await conn.scalar(
            text(
                f"SELECT COUNT(*) FROM {table} "  # noqa: S608
                "WHERE created_at < DATE_SUB(NOW(), INTERVAL :days DAY)"
            ),
            {"days": retention_days},
        )
        counted[table] = int(count or 0)
        if apply and counted[table]:
            await conn.execute(
                text(
                    f"DELETE FROM {table} "  # noqa: S608
                    "WHERE created_at < DATE_SUB(NOW(), INTERVAL :days DAY)"
                ),
                {"days": retention_days},
            )
    return counted


async def main() -> None:
    apply = "--apply" in sys.argv[1:]
    async with engine.begin() as conn:
        counted = await prune(conn, apply=apply)
    await engine.dispose()

    mode = "삭제 완료" if apply else "dry-run (삭제하려면 --apply)"
    print(f"# STS 이벤트 보존 정리 (#373) — 보존 {RETENTION_DAYS}일, {mode}")
    for table, count in counted.items():
        print(f"- {table}: {count}건")


if __name__ == "__main__":
    asyncio.run(main())

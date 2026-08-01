# =====================================================================================
# 근력 안전망 카드 주간 발화율 리포트 (#373 — 지표 정의의 실행 형태).
#
# 사용법 (서버 또는 DB 접속 가능한 곳에서):
#   uv run --no-sync python -m scripts.sts_overlay_report
#
# 지표 정의 (단일 원천: docs/sts_overlay_metrics.md):
#   주간 발화율 = 카드 노출 사용자 수 / 점수 화면 조회 사용자 수  (집계 단위: 사용자·ISO 주)
#   - 분자: sts_overlay_events   에서 해당 주 COUNT(DISTINCT user_id)
#   - 분모: sts_score_view_events 에서 해당 주 COUNT(DISTINCT user_id)
#   - 중복 행(fire-and-forget 재시도)은 DISTINCT 로 흡수한다 — 멱등키를 두지 않는 결정의 전제.
#   - 주 경계는 YEARWEEK(..., 3) = ISO 8601(월요일 시작), DB 저장 시각(운영 세션 tz = KST) 기준.
#   - 분모 이벤트가 아직 앱에 배선되지 않은 주는 발화율을 '-' 로 표기한다(0% 로 오독 방지).
#
# 읽기 전용(SELECT only) — 운영 DB 에 안전하다. 보존 정리는 scripts.prune_sts_events 가 담당.
# =====================================================================================
from __future__ import annotations

import asyncio
from typing import Any

from sqlalchemy import text

from app.core.db.session import engine

DEFAULT_WEEKS = 12

# 두 쿼리 모두 WHERE created_at 범위 + user_id 만 읽는다 →
# (created_at, user_id) 커버링 인덱스로 도는 것을 EXPLAIN 으로 확인함(docs/sts_overlay_metrics.md §5).
WEEKLY_EXPOSED_USERS = text(
    """
    SELECT YEARWEEK(created_at, 3) AS iso_week, COUNT(DISTINCT user_id) AS exposed_users
    FROM sts_overlay_events
    WHERE created_at >= DATE_SUB(NOW(), INTERVAL :weeks WEEK)
    GROUP BY iso_week
    ORDER BY iso_week
    """
)

WEEKLY_VIEWER_USERS = text(
    """
    SELECT YEARWEEK(created_at, 3) AS iso_week, COUNT(DISTINCT user_id) AS viewer_users
    FROM sts_score_view_events
    WHERE created_at >= DATE_SUB(NOW(), INTERVAL :weeks WEEK)
    GROUP BY iso_week
    ORDER BY iso_week
    """
)

# 컷 재조정 근거(#373 §저장 사유): 노출 시점 입력값 스냅샷의 분포. 행 수(events)와
# 사용자 수(users)를 분리 표기해 재시도 중복이 사용자 수로 오독되지 않게 한다.
EXPOSURE_BREAKDOWN = text(
    """
    SELECT
        tier,
        COALESCE(score_band, '(없음)') AS score_band,
        COUNT(*)                       AS events,
        COUNT(DISTINCT user_id)        AS users,
        ROUND(AVG(sts_sec), 2)         AS avg_sts_sec,
        ROUND(AVG(bmi), 1)             AS avg_bmi
    FROM sts_overlay_events
    WHERE created_at >= DATE_SUB(NOW(), INTERVAL :weeks WEEK)
    GROUP BY tier, score_band
    ORDER BY tier, score_band
    """
)


def merge_weekly_rate(exposed: list[dict[str, Any]], viewers: list[dict[str, Any]]) -> list[dict[str, object]]:
    """주별 분자·분모를 병합해 발화율 행을 만든다(어느 한쪽만 있는 주도 표기). 테스트 진입점."""
    exposed_by_week = {int(r["iso_week"]): int(r["exposed_users"]) for r in exposed}
    viewers_by_week = {int(r["iso_week"]): int(r["viewer_users"]) for r in viewers}
    rows: list[dict[str, object]] = []
    for week in sorted(set(exposed_by_week) | set(viewers_by_week)):
        numerator = exposed_by_week.get(week, 0)
        denominator = viewers_by_week.get(week, 0)
        rows.append(
            {
                "iso_week": week,
                "노출 사용자": numerator,
                "조회 사용자": denominator,
                # 분모 0 = 아직 조회 이벤트가 없는 주(앱 배선 전 포함) — 0% 가 아니라 산출 불가.
                "발화율": f"{numerator / denominator * 100:.1f}%" if denominator else "-",
            }
        )
    return rows


def _print_table(title: str, rows: list[dict[str, object]]) -> None:
    print(f"\n## {title}")
    if not rows:
        print("(이벤트 없음)")
        return
    headers = list(rows[0].keys())
    print("| " + " | ".join(headers) + " |")
    print("|" + "|".join("---" for _ in headers) + "|")
    for row in rows:
        print("| " + " | ".join(str(v) for v in row.values()) + " |")


async def main(weeks: int = DEFAULT_WEEKS) -> None:
    params = {"weeks": weeks}
    async with engine.connect() as conn:
        exposed = [dict(r) for r in (await conn.execute(WEEKLY_EXPOSED_USERS, params)).mappings()]
        viewers = [dict(r) for r in (await conn.execute(WEEKLY_VIEWER_USERS, params)).mappings()]
        breakdown = [dict(r) for r in (await conn.execute(EXPOSURE_BREAKDOWN, params)).mappings()]
    await engine.dispose()

    print(f"# 근력 안전망 카드 주간 발화율 (#373) — 최근 {weeks}주, ISO 주(KST) 기준")
    _print_table("주간 발화율 (노출 사용자 / 조회 사용자)", merge_weekly_rate(exposed, viewers))
    _print_table("노출 구성 (tier × score_band, 컷 재조정 근거)", breakdown)
    print(
        "\n> 발화율 '-' 는 해당 주 분모(점수 화면 조회 이벤트) 0건 — 앱 분모 배선(#373 후속) 전 기간이거나 "
        "조회가 없던 주다. 정책·저장 사유는 docs/sts_overlay_metrics.md 참조."
    )


if __name__ == "__main__":
    asyncio.run(main())

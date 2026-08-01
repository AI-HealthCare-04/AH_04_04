# =====================================================================================
# 예측 피드백 집계 리포트 (#357 경량 C — "수집 → 집계 → 검토 → 개선 기록" 루프의 집계 단계).
#
# 사용법 (서버 또는 DB 접속 가능한 곳에서):
#   uv run --no-sync python -m scripts.feedback_report
#
# 무엇을 집계하나:
#   1) 모델 버전 × 점수 구간(score_band)별 응답 분포와 **불일치율**(different / 전체)
#   2) 응답 provider 분포 (guest / google / kakao) — 시연·심사 자료에서 게스트 비중 확인용
#
# 원칙 (이슈 #357 결정안):
#   - is_test = TRUE 응답은 제외한다 — 시연·QA 응답이 '실제 사용자 N'에 섞이면 안 된다.
#     (시연 계정 응답은 psql/mysql 에서 UPDATE prediction_feedbacks SET is_test=1 로 미리 마킹)
#   - 이 수치는 모델 정답 라벨이 아니라 **주관적 체감 신호**다. 불일치율이 높은 구간은
#     '추가 검증 후보'로만 쓰고, 검토·개선 결정은 docs/ml/feedback_loop_357.md 에 기록한다.
#
# 읽기 전용(SELECT only) — 운영 DB 에 안전하다.
# =====================================================================================
from __future__ import annotations

import asyncio

from sqlalchemy import text

from app.core.db.session import engine

DISAGREEMENT_BY_MODEL_AND_BAND = text(
    """
    SELECT
        rp.model_version,
        COALESCE(rp.score_band, '(없음)')                          AS score_band,
        COUNT(*)                                                   AS responses,
        SUM(pf.response = 'similar')                               AS similar_cnt,
        SUM(pf.response = 'unsure')                                AS unsure_cnt,
        SUM(pf.response = 'different')                             AS different_cnt,
        ROUND(SUM(pf.response = 'different') / COUNT(*) * 100, 1)  AS disagreement_pct,
        SUM(pf.reason = 'too_high')                                AS too_high_cnt,
        SUM(pf.reason = 'too_low')                                 AS too_low_cnt
    FROM prediction_feedbacks pf
    JOIN risk_predictions rp ON rp.prediction_id = pf.prediction_id
    WHERE pf.is_test = FALSE
    GROUP BY rp.model_version, rp.score_band
    ORDER BY rp.model_version, rp.score_band
    """
)

RESPONSES_BY_PROVIDER = text(
    """
    SELECT u.provider, COUNT(*) AS responses
    FROM prediction_feedbacks pf
    JOIN risk_predictions rp ON rp.prediction_id = pf.prediction_id
    JOIN users u ON u.user_id = rp.user_id
    WHERE pf.is_test = FALSE
    GROUP BY u.provider
    ORDER BY responses DESC
    """
)

TOTALS = text(
    """
    SELECT
        COUNT(*)                                 AS stored_total,
        COALESCE(SUM(pf.is_test = TRUE), 0)      AS test_marked,
        COALESCE(SUM(pf.is_test = FALSE), 0)     AS real_total
    FROM prediction_feedbacks pf
    """
)


def _print_table(title: str, rows: list[dict[str, object]]) -> None:
    print(f"\n## {title}")
    if not rows:
        print("(응답 없음)")
        return
    headers = list(rows[0].keys())
    print("| " + " | ".join(headers) + " |")
    print("|" + "|".join("---" for _ in headers) + "|")
    for row in rows:
        print("| " + " | ".join(str(v) for v in row.values()) + " |")


async def main() -> None:
    async with engine.connect() as conn:
        totals = (await conn.execute(TOTALS)).mappings().one()
        by_model = [dict(r) for r in (await conn.execute(DISAGREEMENT_BY_MODEL_AND_BAND)).mappings()]
        by_provider = [dict(r) for r in (await conn.execute(RESPONSES_BY_PROVIDER)).mappings()]
    await engine.dispose()

    print("# 예측 피드백 집계 (#357) — 실제 사용자 응답 (is_test 제외)")
    # 심사 자료의 '실제 사용자 N'은 real_total 이다 — 저장 전체(stored_total)에는 QA 응답이 섞여
    # 있으므로 그대로 복사하면 안 된다(리뷰 반영: 세 수치를 분리 표기).
    print(
        f"\n실제 집계 대상 {totals['real_total']}건 "
        f"(저장 전체 {totals['stored_total']}건 - 시연·QA 마킹 {totals['test_marked']}건)"
    )
    _print_table("모델 버전 × 점수 구간별 불일치율", by_model)
    _print_table("provider 별 응답 수", by_provider)
    print(
        "\n> 이 수치는 주관적 체감 신호이며 모델 정답 라벨이 아니다(#357). "
        "불일치율이 높은 구간은 docs/ml/feedback_loop_357.md 의 검토 절차로 넘긴다."
    )


if __name__ == "__main__":
    asyncio.run(main())

"""Minimize stored health data: promote cohort sex, drop raw input snapshots (#408).

1차 검토 피드백(서버 보관 최소화) 반영. 예측 1건마다 키·몸무게·허리둘레 등 건강 원본이
`risk_predictions.input_snapshot` 에 JSON 으로 복제 저장되고 있었다.

런타임에 그 스냅샷을 읽는 곳은 또래 분포(get_cohort_distribution) 하나뿐이고, 필요한 값은
조회 키(나이 키·성별)뿐이다. 나이 키는 이미 `score_cohort_age` 컬럼에 있으므로 **성별만 컬럼으로
승격**하면 원본 없이 동작한다.

순서가 중요하다: 파생값을 먼저 보존한 뒤에 원본을 지운다(지영 리뷰) — 반대로 하면 복구 불가.

Revision ID: 0021_drop_input_snapshot
Revises: 0020_mission_bonus
Create Date: 2026-08-03 18:30:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0021_drop_input_snapshot"
down_revision: str = "0020_mission_bonus"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

AGE_TOPCODE = 80


def upgrade() -> None:
    # ① 파생값 보존 — 조회 키의 성별을 컬럼으로 승격.
    op.add_column("risk_predictions", sa.Column("score_cohort_sex", sa.Integer(), nullable=True))
    op.execute(
        """
        UPDATE risk_predictions
        SET score_cohort_sex = CAST(JSON_UNQUOTE(JSON_EXTRACT(input_snapshot, '$.sex')) AS UNSIGNED)
        WHERE input_snapshot IS NOT NULL
          AND JSON_EXTRACT(input_snapshot, '$.sex') IS NOT NULL
        """
    )
    # ② 나이 키가 비어 있는 오래된 행도 스냅샷에서 채운다(score_cohort_age 도입 이전 행).
    #    스냅샷의 age 는 normalize_features 가 이미 80 top-coding 한 값이라 그대로 반올림하면 된다
    #    (_cohort_age_key 와 같은 규칙: >=80 이면 '80+', 아니면 정수 문자열).
    op.execute(
        f"""
        UPDATE risk_predictions
        SET score_cohort_age = CASE
            WHEN ROUND(JSON_EXTRACT(input_snapshot, '$.age')) >= {AGE_TOPCODE} THEN '{AGE_TOPCODE}+'
            ELSE CAST(CAST(ROUND(JSON_EXTRACT(input_snapshot, '$.age')) AS UNSIGNED) AS CHAR)
        END
        WHERE score_cohort_age IS NULL
          AND input_snapshot IS NOT NULL
          AND JSON_EXTRACT(input_snapshot, '$.age') IS NOT NULL
        """
    )
    # ③ 원본 제거 — 신규 저장 중단(서비스 코드)과 함께 기존 행도 비운다. NOT NULL 을 먼저 푼다.
    op.alter_column("risk_predictions", "input_snapshot", existing_type=sa.JSON(), nullable=True)
    op.execute("UPDATE risk_predictions SET input_snapshot = NULL")


def downgrade() -> None:
    # 원본 입력은 의도적으로 파기했으므로 되돌릴 수 없다. 컬럼 제약만 원복하되, NOT NULL 복원은
    #   빈 JSON 으로 채워야 가능하다(값 자체는 복구 불가 — 이 마이그레이션의 목적이 그것이다).
    op.execute("UPDATE risk_predictions SET input_snapshot = JSON_OBJECT() WHERE input_snapshot IS NULL")
    op.alter_column("risk_predictions", "input_snapshot", existing_type=sa.JSON(), nullable=False)
    op.drop_column("risk_predictions", "score_cohort_sex")

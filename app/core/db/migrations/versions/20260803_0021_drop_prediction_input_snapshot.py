"""Minimize stored health data: promote cohort sex, drop raw input snapshots (#408).

1차 검토 피드백(서버 보관 최소화) 반영. 예측 1건마다 키·몸무게·허리둘레 등 건강 원본이
`risk_predictions.input_snapshot` 에 JSON 으로 복제 저장되고 있었다.

런타임에 그 스냅샷을 읽는 곳은 또래 분포(get_cohort_distribution) 하나뿐이고, 필요한 값은
조회 키(나이 키·성별)뿐이다. 나이 키는 이미 `score_cohort_age` 컬럼에 있으므로 **성별만 컬럼으로
승격**하면 원본 없이 동작한다.

순서가 핵심이다(지영 리뷰 P1): 파생값을 먼저 보존하고, **보존이 완전한지 검증한 뒤에만** 원본을
지운다. 삭제는 되돌릴 수 없으므로 한 건이라도 백필이 어긋나면 DDL 이전에 실패시킨다
(0006·0008 의 preflight 가드와 같은 방식). scripts/check_migrations.py 가 fixture 로 못박는다.

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
AGE_MIN = 65
# 모델의 성별 인코딩(app/ml/predictor.py `_normalize_sex`): male=1, female=2. 0 이나 그 밖의 값은
#   코호트표에 존재하지 않는 키라, 그대로 두면 원본이 사라진 뒤 또래 분포가 영구히 실패한다.
VALID_SEX = (1, 2)

# 백필이 끝난 뒤 '원본이 있었는데 조회 키를 복원하지 못한' 행. 한 건이라도 있으면 삭제하지 않는다.
_UNRECOVERABLE_ROWS = f"""
    SELECT COUNT(*) FROM risk_predictions
    WHERE input_snapshot IS NOT NULL
      AND (
            score_cohort_sex IS NULL
         OR score_cohort_sex NOT IN {VALID_SEX}
         OR score_cohort_age IS NULL
         OR NOT (
                score_cohort_age = '{AGE_TOPCODE}+'
                OR (score_cohort_age REGEXP '^[0-9]+$' AND CAST(score_cohort_age AS UNSIGNED) >= {AGE_MIN})
            )
      )
"""


def upgrade() -> None:
    # ① 파생값 보존 — 조회 키의 성별을 컬럼으로 승격. 모델 인코딩(1·2)이 아닌 값은 채우지 않는다:
    #    잘못된 값을 넣으면 '백필됨'으로 보여 아래 가드를 통과해 버린다.
    op.add_column("risk_predictions", sa.Column("score_cohort_sex", sa.Integer(), nullable=True))
    op.execute(
        f"""
        UPDATE risk_predictions
        SET score_cohort_sex = CAST(JSON_UNQUOTE(JSON_EXTRACT(input_snapshot, '$.sex')) AS UNSIGNED)
        WHERE input_snapshot IS NOT NULL
          AND JSON_TYPE(JSON_EXTRACT(input_snapshot, '$.sex')) IN ('INTEGER', 'UNSIGNED INTEGER')
          AND CAST(JSON_UNQUOTE(JSON_EXTRACT(input_snapshot, '$.sex')) AS UNSIGNED) IN {VALID_SEX}
        """
    )
    # ② 나이 키가 비어 있는 오래된 행도 스냅샷에서 채운다(score_cohort_age 도입 이전 행).
    #    스냅샷의 age 는 normalize_features 가 이미 80 top-coding 한 값이라 반올림하면 된다
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
          AND JSON_TYPE(JSON_EXTRACT(input_snapshot, '$.age')) IN
              ('INTEGER', 'UNSIGNED INTEGER', 'DOUBLE', 'DECIMAL')
        """
    )

    # ③ [안전가드] 보존이 완전한지 **삭제 전에** 검증한다(지영 리뷰 P1).
    #    JSON 키 누락·타입 불일치·모델 인코딩 아닌 성별이 있으면 조회 키를 복원하지 못한 채 원본만
    #    사라져 또래 분포가 영구히 422/404 가 되고, downgrade 로도 값을 되살릴 수 없다.
    #    그래서 '0건을 가정'하지 않고 검증한다 — 한 건이라도 있으면 DDL 이전에 실패시켜, 대상 환경에서
    #    데이터를 확인·교정한 뒤 재실행하게 만든다. ①②는 멱등이라 재실행이 안전하다.
    unrecoverable = op.get_bind().execute(sa.text(_UNRECOVERABLE_ROWS)).scalar_one()
    if unrecoverable:
        raise RuntimeError(
            f"0021_drop_input_snapshot 중단: 조회 키를 복원하지 못한 예측이 {unrecoverable}건 있습니다. "
            "원본 스냅샷을 지우면 또래 분포를 되살릴 수 없으므로 삭제하지 않았습니다. "
            "아래로 대상을 확인하고 score_cohort_age/score_cohort_sex(1=male, 2=female)를 교정한 뒤 "
            "재실행하세요:\n"
            "  SELECT prediction_id, score_cohort_age, score_cohort_sex, input_snapshot "
            "FROM risk_predictions WHERE input_snapshot IS NOT NULL AND ("
            "score_cohort_sex IS NULL OR score_cohort_sex NOT IN (1,2) OR score_cohort_age IS NULL);\n"
            "(백필 실패를 조용히 삼키고 원본을 지우지 않기 위한 의도적 실패입니다.)"
        )

    # ④ 원본 제거 — 여기까지 왔다면 모든 행의 조회 키가 보존돼 있다. NOT NULL 을 먼저 푼다.
    op.alter_column("risk_predictions", "input_snapshot", existing_type=sa.JSON(), nullable=True)
    op.execute("UPDATE risk_predictions SET input_snapshot = NULL")


def downgrade() -> None:
    # 원본 입력은 의도적으로 파기했으므로 되돌릴 수 없다. 컬럼 제약만 원복하되, NOT NULL 복원은
    #   빈 JSON 으로 채워야 가능하다(값 자체는 복구 불가 — 이 마이그레이션의 목적이 그것이다).
    op.execute("UPDATE risk_predictions SET input_snapshot = JSON_OBJECT() WHERE input_snapshot IS NULL")
    op.alter_column("risk_predictions", "input_snapshot", existing_type=sa.JSON(), nullable=False)
    op.drop_column("risk_predictions", "score_cohort_sex")

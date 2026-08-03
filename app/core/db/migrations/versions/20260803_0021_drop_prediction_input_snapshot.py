"""Minimize stored health data: promote cohort sex, drop raw input snapshots (#408).

1차 검토 피드백(서버 보관 최소화) 반영. 예측 1건마다 키·몸무게·허리둘레 등 건강 원본이
`risk_predictions.input_snapshot` 에 JSON 으로 복제 저장되고 있었다.

런타임에 그 스냅샷을 읽는 곳은 또래 분포(get_cohort_distribution) 하나뿐이고, 필요한 값은
조회 키(나이 키·성별)뿐이다. 나이 키는 이미 `score_cohort_age` 컬럼에 있으므로 **성별만 컬럼으로
승격**하면 원본 없이 동작한다.

⚠️ 순서와 재실행 가능성이 핵심이다(#410 리뷰 P1).
  - 검증은 **DDL 이전에** 끝낸다. MySQL 은 DDL 이 암시적 커밋이라, 컬럼을 추가한 뒤 실패하면
    revision 은 0020 에 머문 채 컬럼만 남아 재실행이 duplicate column 으로 막힌다. 그래서
    원본 JSON + 기존 `score_cohort_age` 만으로 복원 가능성을 먼저 판정한다.
  - 그럼에도 컬럼 추가는 **조건부**로 둔다. 이전 버전으로 반쯤 적용된 DB 에서도 데이터를 고친 뒤
    같은 DB 에 그대로 재실행할 수 있어야 한다. 백필·삭제도 모두 멱등이다.
  - scripts/check_migrations.py 가 '가드 실패 → 데이터 교정 → 같은 DB 재실행 성공'까지 못박는다.

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

# ⚠️ 아래 조건식은 전부 COALESCE(..., FALSE) 로 감싼다. JSON 키가 없으면 JSON_EXTRACT 가 NULL 을
#   돌려주고 `NULL IN (...)` 은 NULL 이라, 그대로 두면 `NOT <조건>` 이 참이 되지 않아 **복원 불가 행이
#   가드를 통과한다**(SQL 3값 논리). score_cohort_age 가 NULL 인 구행도 같다.

# 실제로 존재하는 조회 키만 유효로 본다: 단일 나이 65..79, 그리고 상단 top-code '80+'.
#   (`_cohort_age_key` 는 80 이상을 '80+' 로 접으므로 '80'·'999' 같은 키는 만들어지지 않는다.)
_VALID_AGE_KEY = (
    f"COALESCE(score_cohort_age = '{AGE_TOPCODE}+'"
    f" OR (score_cohort_age REGEXP '^[0-9]+$'"
    f"     AND CAST(score_cohort_age AS UNSIGNED) BETWEEN {AGE_MIN} AND {AGE_TOPCODE - 1}), FALSE)"
)
# 스냅샷에서 성별을 계약대로 복원할 수 있는가.
_SEX_RECOVERABLE = (
    "COALESCE(JSON_TYPE(JSON_EXTRACT(input_snapshot, '$.sex')) IN ('INTEGER', 'UNSIGNED INTEGER')"
    f" AND CAST(JSON_UNQUOTE(JSON_EXTRACT(input_snapshot, '$.sex')) AS UNSIGNED) IN {VALID_SEX}, FALSE)"
)
# 스냅샷 나이로 지원 범위의 키를 만들 수 있는가(숫자 타입 + 반올림 후 65 이상).
_AGE_RECOVERABLE = (
    "COALESCE(JSON_TYPE(JSON_EXTRACT(input_snapshot, '$.age')) IN "
    "('INTEGER', 'UNSIGNED INTEGER', 'DOUBLE', 'DECIMAL')"
    f" AND ROUND(JSON_EXTRACT(input_snapshot, '$.age')) >= {AGE_MIN}, FALSE)"
)

# [preflight] 원본이 남아 있는 행 중, 조회 키를 복원할 수 없는 것. **DDL 이전에** 센다.
_UNRECOVERABLE_ROWS = f"""
    SELECT COUNT(*) FROM risk_predictions
    WHERE input_snapshot IS NOT NULL
      AND (NOT {_SEX_RECOVERABLE} OR NOT ({_VALID_AGE_KEY} OR {_AGE_RECOVERABLE}))
"""

_DIAGNOSTIC_QUERY = (
    "  SELECT prediction_id, score_cohort_age, input_snapshot FROM risk_predictions "
    "WHERE input_snapshot IS NOT NULL AND (NOT " + _SEX_RECOVERABLE + " OR NOT (" + _VALID_AGE_KEY + " OR " + _AGE_RECOVERABLE + "));"
)


def _has_score_cohort_sex(bind: sa.engine.Connection) -> bool:
    """컬럼이 이미 있는지(= 이전 실행이 DDL 까지 갔다가 멈춘 DB인지) 본다."""
    return bool(
        bind.execute(
            sa.text(
                "SELECT COUNT(*) FROM information_schema.columns "
                "WHERE table_schema = DATABASE() AND table_name = 'risk_predictions' "
                "AND column_name = 'score_cohort_sex'"
            )
        ).scalar_one()
    )


def upgrade() -> None:
    bind = op.get_bind()

    # ① [안전가드] 복원 가능성을 **DDL 이전에** 검증한다(#410 리뷰 P1).
    #    한 건이라도 복원 불가면 아무것도 바꾸지 않고 중단한다 — 이 시점에는 스키마·데이터가 그대로라
    #    데이터를 고친 뒤 같은 DB 에 재실행하면 된다.
    unrecoverable = bind.execute(sa.text(_UNRECOVERABLE_ROWS)).scalar_one()
    if unrecoverable:
        raise RuntimeError(
            f"0021_drop_input_snapshot 중단: 조회 키를 복원하지 못하는 예측이 {unrecoverable}건 있습니다. "
            "원본 스냅샷을 지우면 또래 분포를 되살릴 수 없으므로 아무것도 변경하지 않았습니다. "
            "아래로 대상을 확인하고 스냅샷의 sex(1=male, 2=female)·age 를 교정하거나 "
            "score_cohort_age 를 유효한 키(65~79 또는 '80+')로 채운 뒤 재실행하세요:\n"
            f"{_DIAGNOSTIC_QUERY}\n"
            "(백필 실패를 조용히 삼키고 원본을 지우지 않기 위한 의도적 실패입니다.)"
        )

    # ② 파생값 보존 — 조회 키의 성별을 컬럼으로 승격. 컬럼 추가는 조건부라 재실행에도 안전하다.
    if not _has_score_cohort_sex(bind):
        op.add_column("risk_predictions", sa.Column("score_cohort_sex", sa.Integer(), nullable=True))
    op.execute(
        f"""
        UPDATE risk_predictions
        SET score_cohort_sex = CAST(JSON_UNQUOTE(JSON_EXTRACT(input_snapshot, '$.sex')) AS UNSIGNED)
        WHERE input_snapshot IS NOT NULL AND {_SEX_RECOVERABLE}
        """
    )
    # ③ 나이 키가 비어 있거나 잘못된 행은 스냅샷에서 다시 만든다(score_cohort_age 도입 이전 행 포함).
    #    스냅샷의 age 는 normalize_features 가 이미 80 top-coding 한 값이라 반올림하면 된다
    #    (_cohort_age_key 와 같은 규칙: >=80 이면 '80+', 아니면 정수 문자열).
    op.execute(
        f"""
        UPDATE risk_predictions
        SET score_cohort_age = CASE
            WHEN ROUND(JSON_EXTRACT(input_snapshot, '$.age')) >= {AGE_TOPCODE} THEN '{AGE_TOPCODE}+'
            ELSE CAST(CAST(ROUND(JSON_EXTRACT(input_snapshot, '$.age')) AS UNSIGNED) AS CHAR)
        END
        WHERE input_snapshot IS NOT NULL
          AND NOT {_VALID_AGE_KEY}
          AND {_AGE_RECOVERABLE}
        """
    )

    # ④ 원본 제거 — ①에서 전 행의 복원 가능성을 확인했고 ②③이 그대로 채웠다.
    op.alter_column("risk_predictions", "input_snapshot", existing_type=sa.JSON(), nullable=True)
    op.execute("UPDATE risk_predictions SET input_snapshot = NULL")


def downgrade() -> None:
    # 원본 입력은 의도적으로 파기했으므로 되돌릴 수 없다. 컬럼 제약만 원복하되, NOT NULL 복원은
    #   빈 JSON 으로 채워야 가능하다(값 자체는 복구 불가 — 이 마이그레이션의 목적이 그것이다).
    op.execute("UPDATE risk_predictions SET input_snapshot = JSON_OBJECT() WHERE input_snapshot IS NULL")
    op.alter_column("risk_predictions", "input_snapshot", existing_type=sa.JSON(), nullable=False)
    op.drop_column("risk_predictions", "score_cohort_sex")

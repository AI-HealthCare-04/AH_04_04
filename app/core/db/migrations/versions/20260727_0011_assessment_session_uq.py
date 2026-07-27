"""physical_assessments: 세션당 체력검사 1건 유니크(멱등)

응답을 못 받은 앱이 같은 체력검사를 재전송하면 지금은 행이 하나 더 생기고,
세션은 이미 COMPLETED 라 두 번째 요청이 409 를 받는다(#180). 세션당 1건을 DB 유니크로
보장하면, 재전송 경합에서 두 번째 삽입이 막혀 서비스가 기존 결과를 그대로 돌려줄 수 있다
(services/physical_assessment.py, 미션 create_mission_log 와 동일 패턴).

  session_id 로 자연 키를 삼는다(별도 idempotency key 도입 안 함 — 미션 도메인과 일관).
  session_id 가 NULL 인 행(독립 제출)은 MySQL 유니크에서 제외되므로, 온보딩 밖 독립 제출과
  기존 데이터는 영향이 없다.

⚠️ 유니크 생성 전에 기존 중복을 검사한다(0009 와 같은 안전장치).
  NULL 이 아닌 session_id 가 이미 중복이면 DDL 이 실패하는데 원인 파악이 어렵다. 먼저 세어
  건수를 담은 메시지로 중단시킨다(재전송으로 생긴 중복이면 physical_assessment_id 가 큰 쪽을 정리).

Revision ID: 0011_assessment_session_uq
Revises: 0010_exercise_target_minutes
Create Date: 2026-07-27 00:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0011_assessment_session_uq"
down_revision: str | None = "0010_exercise_target_minutes"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_UQ_NAME = "uq_physical_assessments_session_id"

_DUPLICATE_CHECK = sa.text(
    """
    SELECT COUNT(*) FROM (
        SELECT 1
        FROM physical_assessments
        WHERE session_id IS NOT NULL
        GROUP BY session_id
        HAVING COUNT(*) > 1
    ) AS dup
    """
)


def upgrade() -> None:
    bind = op.get_bind()
    duplicated = bind.execute(_DUPLICATE_CHECK).scalar_one()
    if duplicated:
        raise RuntimeError(
            f"physical_assessments 에 session_id 중복이 {duplicated}건 있습니다. 유니크를 만들기 전에 "
            f"어느 행을 남길지 정해 정리해야 합니다. (재전송으로 생긴 중복이라면 "
            f"physical_assessment_id 가 큰 쪽을 지우면 됩니다)"
        )
    op.create_unique_constraint(_UQ_NAME, "physical_assessments", ["session_id"])


def downgrade() -> None:
    op.drop_constraint(_UQ_NAME, "physical_assessments", type_="unique")

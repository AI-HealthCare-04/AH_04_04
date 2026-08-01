"""Add 'bonus' mission type + the single bonus template (all-missions-complete reward).

보너스 포인트는 별도 테이블 없이 mission_logs 한 행으로 적립한다. 포인트 잔액·적립 이력·당일 합계가
모두 mission_logs.earned_points 에서만 파생되므로(단일 원천), 종류만 늘리면 세 경로가 그대로 맞는다.

Revision ID: 0019_mission_bonus
Revises: 0018_prediction_feedbacks
Create Date: 2026-08-01 12:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0019_mission_bonus"
down_revision: str = "0018_prediction_feedbacks"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# mission_templates.mission_type / mission_logs.mission_type 는 서로 다른 ENUM 이름을 쓰지만
#   값 집합은 같다. 'bonus' 를 더한 새 집합과 되돌릴 옛 집합.
_OLD = "'meal','exercise','walking','game'"
_NEW = "'meal','exercise','walking','game','bonus'"

_ENUM_COLUMNS = (
    ("mission_templates", "mission_type"),
    ("mission_logs", "mission_type"),
)

# 보너스 템플릿(단 하나). display_order 는 목록에 안 나오므로 의미가 없지만 NOT NULL 이라 뒤쪽 값을 준다.
#   is_active=True 로 두되 get_active_templates 가 mission_type!=bonus 로 걸러 목록에 노출되지 않는다.
_TITLE = "모든 미션 완료 보너스"
_DESCRIPTION = "그날 미션을 모두 완료하면 드리는 추가 포인트예요."
_REWARD_POINTS = 10


def upgrade() -> None:
    for table, column in _ENUM_COLUMNS:
        op.execute(f"ALTER TABLE {table} MODIFY COLUMN {column} ENUM({_NEW}) NOT NULL")

    # 이미 있으면(재실행) 넣지 않는다 — mission_type 이 bonus 인 행은 하나만 유지한다.
    op.execute(
        sa.text(
            """
            INSERT INTO mission_templates
                (mission_type, title, description, level, display_order, default_target_value,
                 target_unit, requires_safety_notice, daily_count_limit, reward_points,
                 requires_kidney_check, is_active, created_at, updated_at)
            SELECT 'bonus', :title, :description, 'normal', 90, 1,
                   'count', 0, 1, :reward_points,
                   0, 1, NOW(), NOW()
            FROM DUAL
            WHERE NOT EXISTS (SELECT 1 FROM mission_templates WHERE mission_type = 'bonus')
            """
        ).bindparams(title=_TITLE, description=_DESCRIPTION, reward_points=_REWARD_POINTS)
    )


def downgrade() -> None:
    # 값을 지우기 전에 그 값을 쓰는 행을 먼저 없애야 ENUM 축소가 실패하지 않는다.
    #   보너스 로그는 적립 이력이라 지우면 포인트 잔액이 줄지만, 'bonus' 를 못 쓰는 스키마로 되돌리는
    #   downgrade 의 정의상 함께 되돌리는 것이 맞다.
    op.execute("DELETE FROM mission_logs WHERE mission_type = 'bonus'")
    op.execute("DELETE FROM mission_templates WHERE mission_type = 'bonus'")
    for table, column in _ENUM_COLUMNS:
        op.execute(f"ALTER TABLE {table} MODIFY COLUMN {column} ENUM({_OLD}) NOT NULL")

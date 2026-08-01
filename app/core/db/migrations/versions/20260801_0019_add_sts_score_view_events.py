"""Add sts_score_view_events (발화율 분모) + 기간 집계 인덱스 (#373).

Revision ID: 0019_sts_score_view
Revises: 0018_prediction_feedbacks
Create Date: 2026-08-01 21:10:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0019_sts_score_view"
down_revision: str = "0018_prediction_feedbacks"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # 분모 이벤트: 근력 점수 화면 조회. '누가/언제'만 저장한다(최소 수집, #373).
    op.create_table(
        "sts_score_view_events",
        sa.Column("event_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("event_id"),
    )
    op.create_index("ix_sts_score_view_events_user_id", "sts_score_view_events", ["user_id"])
    op.create_index("ix_sts_score_view_events_created_user", "sts_score_view_events", ["created_at", "user_id"])
    # 주간 발화율 집계(WHERE created_at 범위 + COUNT(DISTINCT user_id))가 커버링 인덱스로 돌게 한다.
    op.create_index("ix_sts_overlay_events_created_user", "sts_overlay_events", ["created_at", "user_id"])


def downgrade() -> None:
    op.drop_index("ix_sts_overlay_events_created_user", table_name="sts_overlay_events")
    # sts_score_view_events 는 drop_table 이 인덱스·FK 를 함께 제거한다(0017 과 동일 사유:
    # user_id 인덱스는 FK 가 필요로 해 개별 DROP INDEX 가 MySQL 1553 으로 거부됨).
    op.drop_table("sts_score_view_events")

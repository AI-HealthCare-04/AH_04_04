"""Add prediction_feedbacks table (#357 사용자 피드백 옵션 B).

Revision ID: 0018_prediction_feedbacks
Revises: 0017_sts_overlay_events
Create Date: 2026-08-01 18:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0018_prediction_feedbacks"
down_revision: str = "0017_sts_overlay_events"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "prediction_feedbacks",
        sa.Column("feedback_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("prediction_id", sa.BigInteger(), nullable=False),
        sa.Column(
            "response",
            sa.Enum("similar", "unsure", "different", name="feedback_response_enum"),
            nullable=False,
        ),
        sa.Column(
            "reason",
            sa.Enum("too_high", "too_low", "other", name="feedback_reason_enum"),
            nullable=True,
        ),
        sa.Column("is_test", sa.Boolean(), server_default=sa.text("0"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.ForeignKeyConstraint(["prediction_id"], ["risk_predictions.prediction_id"]),
        sa.PrimaryKeyConstraint("feedback_id"),
        # '예측 1건당 응답 1회'를 DB 차원에서 보장한다(지영 리뷰 — UI 정책만으로는 부족).
        sa.UniqueConstraint("prediction_id", name="uq_prediction_feedbacks_prediction_id"),
    )


def downgrade() -> None:
    op.drop_table("prediction_feedbacks")

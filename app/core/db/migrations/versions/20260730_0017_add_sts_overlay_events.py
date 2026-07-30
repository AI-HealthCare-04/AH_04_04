"""Add sts_overlay_events table (§3.4 safety-net card exposure telemetry).

Revision ID: 0017_sts_overlay_events
Revises: 0016_muscle_score_fields
Create Date: 2026-07-30 10:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0017_sts_overlay_events"
down_revision: str = "0016_muscle_score_fields"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "sts_overlay_events",
        sa.Column("event_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("tier", sa.String(length=10), nullable=False),
        sa.Column("sts_sec", sa.Numeric(5, 2), nullable=True),
        sa.Column("bmi", sa.Numeric(4, 1), nullable=True),
        sa.Column("score_band", sa.String(length=20), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("event_id"),
    )
    op.create_index("ix_sts_overlay_events_user_id", "sts_overlay_events", ["user_id"])


def downgrade() -> None:
    op.drop_index("ix_sts_overlay_events_user_id", table_name="sts_overlay_events")
    op.drop_table("sts_overlay_events")

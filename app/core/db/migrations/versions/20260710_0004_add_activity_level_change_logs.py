"""add activity level change logs

Revision ID: 0004_add_activity_change_logs
Revises: 0003_align_level_reason_enum
Create Date: 2026-07-10 00:00:00
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "0004_add_activity_change_logs"
down_revision: str | None = "0003_align_level_reason_enum"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "activity_level_change_logs",
        sa.Column("level_change_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("from_level", sa.Enum("easy", "normal", "hard", name="activity_level_enum"), nullable=False),
        sa.Column("to_level", sa.Enum("easy", "normal", "hard", name="activity_level_enum"), nullable=False),
        sa.Column(
            "reason_type",
            sa.Enum("rule", "llm_recommendation", "user_request", name="reason_type_enum"),
            nullable=False,
        ),
        sa.Column("reason_text", sa.Text(), nullable=True),
        sa.Column("accepted_by_user", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("level_change_id"),
    )
    op.create_index("ix_activity_level_change_logs_user_id", "activity_level_change_logs", ["user_id"])


def downgrade() -> None:
    # Dropping the table lets MySQL remove FK-backed indexes with it.
    # Dropping the index first can fail with errno 1553.
    op.drop_table("activity_level_change_logs")

"""Use activity day counts for risk prediction.

Revision ID: 0014_activity_day_counts
Revises: 0013_remove_derived_columns
Create Date: 2026-07-29 00:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0014_activity_day_counts"
down_revision: str | None = "0013_remove_derived_columns"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.alter_column(
        "risk_predictions",
        "model_version",
        existing_type=sa.String(length=50),
        type_=sa.String(length=100),
        nullable=False,
    )
    op.add_column("health_profiles", sa.Column("walk_days", sa.Integer(), nullable=True))
    op.add_column("health_profiles", sa.Column("musc_days", sa.Integer(), nullable=True))
    op.execute("UPDATE health_profiles SET walk_days = CASE WHEN walking_practice THEN 5 ELSE 0 END")
    op.execute("UPDATE health_profiles SET musc_days = CASE WHEN strength_exercise THEN 2 ELSE 0 END")
    op.alter_column("health_profiles", "walk_days", existing_type=sa.Integer(), nullable=False)
    op.alter_column("health_profiles", "musc_days", existing_type=sa.Integer(), nullable=False)
    op.drop_column("health_profiles", "walking_practice")
    op.drop_column("health_profiles", "strength_exercise")


def downgrade() -> None:
    op.alter_column(
        "risk_predictions",
        "model_version",
        existing_type=sa.String(length=100),
        type_=sa.String(length=50),
        nullable=False,
    )
    op.add_column(
        "health_profiles",
        sa.Column("strength_exercise", sa.Boolean(), nullable=True),
    )
    op.add_column(
        "health_profiles",
        sa.Column("walking_practice", sa.Boolean(), nullable=True),
    )
    op.execute("UPDATE health_profiles SET walking_practice = walk_days >= 5")
    op.execute("UPDATE health_profiles SET strength_exercise = musc_days >= 2")
    op.alter_column("health_profiles", "walking_practice", existing_type=sa.Boolean(), nullable=False)
    op.alter_column("health_profiles", "strength_exercise", existing_type=sa.Boolean(), nullable=False)
    op.drop_column("health_profiles", "musc_days")
    op.drop_column("health_profiles", "walk_days")

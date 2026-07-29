"""Remove redundant derived columns.

The dashboard derives moderate-equivalent minutes from the physical activity
source fields at read time. Meal completion and category count are derived
from mission_logs and protein_foods, so their duplicated columns have no
runtime read path.

Revision ID: 0013_remove_derived_columns
Revises: 0012_remove_unused_schema
Create Date: 2026-07-29 00:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0013_remove_derived_columns"
down_revision: str | None = "0012_remove_unused_schema"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.drop_column("physical_activity_logs", "moderate_equivalent_min")
    op.drop_column("meal_logs", "protein_meal_count")
    op.drop_column("meal_logs", "counted_for_daily")


def downgrade() -> None:
    op.add_column(
        "meal_logs",
        sa.Column("counted_for_daily", sa.Boolean(), nullable=False, server_default=sa.false()),
    )
    op.alter_column(
        "meal_logs",
        "counted_for_daily",
        existing_type=sa.Boolean(),
        nullable=False,
        server_default=None,
    )
    op.add_column(
        "meal_logs",
        sa.Column("protein_meal_count", sa.Integer(), nullable=False, server_default="0"),
    )
    op.alter_column(
        "meal_logs",
        "protein_meal_count",
        existing_type=sa.Integer(),
        nullable=False,
        server_default=None,
    )
    op.add_column(
        "physical_activity_logs",
        sa.Column("moderate_equivalent_min", sa.Numeric(6, 2), nullable=True),
    )

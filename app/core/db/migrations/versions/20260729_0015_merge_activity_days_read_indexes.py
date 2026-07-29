"""Merge activity day counts and read index optimization heads.

Revision ID: 0015_merge_activity_days_read_indexes
Revises: 0014_activity_day_counts, 0014_optimize_read_indexes
Create Date: 2026-07-29 00:00:00
"""

from collections.abc import Sequence

revision: str = "0015_merge_activity_days_read_indexes"
down_revision: tuple[str, str] = (
    "0014_activity_day_counts",
    "0014_optimize_read_indexes",
)
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    pass


def downgrade() -> None:
    pass

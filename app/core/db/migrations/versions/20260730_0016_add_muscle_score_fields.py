"""Add peer-relative muscle score fields to risk predictions.

Revision ID: 0016_muscle_score_fields
Revises: 0015_merge_0014_heads
Create Date: 2026-07-30 00:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0016_muscle_score_fields"
down_revision: str = "0015_merge_0014_heads"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("risk_predictions", sa.Column("muscle_score", sa.Integer(), nullable=True))
    op.add_column("risk_predictions", sa.Column("score_band", sa.String(length=20), nullable=True))
    op.add_column("risk_predictions", sa.Column("score_p_low", sa.Numeric(8, 5), nullable=True))
    op.add_column("risk_predictions", sa.Column("score_p_high", sa.Numeric(8, 5), nullable=True))
    op.add_column("risk_predictions", sa.Column("score_cohort_age", sa.String(length=4), nullable=True))


def downgrade() -> None:
    op.drop_column("risk_predictions", "score_cohort_age")
    op.drop_column("risk_predictions", "score_p_high")
    op.drop_column("risk_predictions", "score_p_low")
    op.drop_column("risk_predictions", "score_band")
    op.drop_column("risk_predictions", "muscle_score")

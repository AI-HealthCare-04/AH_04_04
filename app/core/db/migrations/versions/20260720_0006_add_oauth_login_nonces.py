"""add one-time OAuth login nonce storage

Revision ID: 0006_oauth_login_nonces
Revises: 0005_backfill_kidney_check
Create Date: 2026-07-20 00:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0006_oauth_login_nonces"
down_revision: str | None = "0005_backfill_kidney_check"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "oauth_login_nonces",
        sa.Column("nonce_hash", sa.String(length=64), nullable=False),
        sa.Column("provider", sa.String(length=20), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False
        ),
        sa.PrimaryKeyConstraint("nonce_hash"),
    )


def downgrade() -> None:
    op.drop_table("oauth_login_nonces")

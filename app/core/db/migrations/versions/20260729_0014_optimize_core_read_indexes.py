"""Optimize indexes for user-scoped date and latest-row reads.

Replace redundant single-column user indexes with composite indexes that still
support the user foreign keys while also covering the repository filter/order
patterns. Add a timestamp index for the rolling OAuth nonce cleanup.

Revision ID: 0014_optimize_read_indexes
Revises: 0013_remove_derived_columns
Create Date: 2026-07-29 00:00:00
"""

from collections.abc import Sequence

from alembic import op

revision: str = "0014_optimize_read_indexes"
down_revision: str | None = "0013_remove_derived_columns"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # Create replacements first so MySQL foreign keys always retain a
    # leftmost user_id index while the redundant single-column indexes drop.
    op.create_index(
        "ix_mission_logs_user_created_at",
        "mission_logs",
        ["user_id", "created_at"],
    )
    op.create_index(
        "ix_health_profiles_user_created_id",
        "health_profiles",
        ["user_id", "created_at", "profile_id"],
    )
    op.create_index(
        "ix_risk_predictions_user_created_id",
        "risk_predictions",
        ["user_id", "created_at", "prediction_id"],
    )
    op.create_index(
        "ix_health_check_sessions_user_status_id",
        "health_check_sessions",
        ["user_id", "status", "session_id"],
    )
    op.create_index(
        "ix_oauth_login_nonces_created_at",
        "oauth_login_nonces",
        ["created_at"],
    )

    op.drop_index("ix_mission_logs_user_id", table_name="mission_logs")
    op.drop_index("ix_health_profiles_user_id", table_name="health_profiles")
    op.drop_index("ix_risk_predictions_user_id", table_name="risk_predictions")
    op.drop_index("ix_health_check_sessions_user_id", table_name="health_check_sessions")
    op.drop_index("ix_daily_activity_summaries_user_id", table_name="daily_activity_summaries")


def downgrade() -> None:
    # Restore the original user indexes before removing their composite
    # replacements so foreign-key index coverage is never interrupted.
    op.create_index("ix_mission_logs_user_id", "mission_logs", ["user_id"])
    op.create_index("ix_health_profiles_user_id", "health_profiles", ["user_id"])
    op.create_index("ix_risk_predictions_user_id", "risk_predictions", ["user_id"])
    op.create_index("ix_health_check_sessions_user_id", "health_check_sessions", ["user_id"])
    op.create_index(
        "ix_daily_activity_summaries_user_id",
        "daily_activity_summaries",
        ["user_id"],
    )

    op.drop_index("ix_oauth_login_nonces_created_at", table_name="oauth_login_nonces")
    op.drop_index(
        "ix_health_check_sessions_user_status_id",
        table_name="health_check_sessions",
    )
    op.drop_index("ix_risk_predictions_user_created_id", table_name="risk_predictions")
    op.drop_index("ix_health_profiles_user_created_id", table_name="health_profiles")
    op.drop_index("ix_mission_logs_user_created_at", table_name="mission_logs")

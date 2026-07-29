"""Remove unused point balance storage and obsolete MVP columns.

Points are derived from mission_logs.earned_points, so point_balances is a
second source of truth with no runtime read/write path. Sensor sessions remain
because the walking flow stores accelerometer summaries, but sensor_type is
redundant now that accelerometer is the only supported sensor.

Revision ID: 0012_remove_unused_schema
Revises: 0011_assessment_session_uq
Create Date: 2026-07-29 00:00:00
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0012_remove_unused_schema"
down_revision: str | None = "0011_assessment_session_uq"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.drop_table("point_balances")

    op.drop_column("personalized_settings", "notification_enabled")

    op.drop_column("mission_templates", "exercise_category")
    op.drop_column("mission_templates", "activity_type")
    op.drop_column("mission_templates", "estimated_intensity")
    op.drop_column("mission_templates", "met_value")
    op.drop_column("mission_templates", "evidence_message")
    op.drop_column("mission_templates", "is_repeatable")

    op.drop_column("sensor_sessions", "sensor_type")


def downgrade() -> None:
    op.add_column(
        "sensor_sessions",
        sa.Column(
            "sensor_type",
            sa.Enum("accelerometer", "gyroscope", "step_counter", name="sensor_type_enum"),
            nullable=False,
            server_default="accelerometer",
        ),
    )
    op.alter_column(
        "sensor_sessions",
        "sensor_type",
        existing_type=sa.Enum("accelerometer", "gyroscope", "step_counter", name="sensor_type_enum"),
        nullable=False,
        server_default=None,
    )

    op.add_column(
        "mission_templates",
        sa.Column("is_repeatable", sa.Boolean(), nullable=False, server_default=sa.true()),
    )
    op.alter_column(
        "mission_templates",
        "is_repeatable",
        existing_type=sa.Boolean(),
        nullable=False,
        server_default=None,
    )
    op.add_column("mission_templates", sa.Column("evidence_message", sa.Text(), nullable=True))
    op.add_column("mission_templates", sa.Column("met_value", sa.Numeric(4, 2), nullable=True))
    op.add_column(
        "mission_templates",
        sa.Column(
            "estimated_intensity",
            sa.Enum("low", "moderate", "high", name="intensity_enum"),
            nullable=True,
        ),
    )
    op.add_column(
        "mission_templates",
        sa.Column(
            "activity_type",
            sa.Enum(
                "walking",
                "chair_stand",
                "seated_exercise",
                "standing_exercise",
                "stretching",
                name="activity_type_enum",
            ),
            nullable=True,
        ),
    )
    op.add_column(
        "mission_templates",
        sa.Column(
            "exercise_category",
            sa.Enum("warm_up", "seated", "standing", "cool_down", name="exercise_category_enum"),
            nullable=True,
        ),
    )

    op.add_column(
        "personalized_settings",
        sa.Column("notification_enabled", sa.Boolean(), nullable=False, server_default=sa.true()),
    )
    op.alter_column(
        "personalized_settings",
        "notification_enabled",
        existing_type=sa.Boolean(),
        nullable=False,
        server_default=None,
    )

    op.create_table(
        "point_balances",
        sa.Column("point_balance_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("current_points", sa.Integer(), nullable=False),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("point_balance_id"),
        sa.UniqueConstraint("user_id"),
    )

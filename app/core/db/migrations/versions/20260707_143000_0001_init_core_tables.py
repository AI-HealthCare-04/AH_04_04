"""init core tables

Revision ID: 0001_init_core_tables
Revises:
Create Date: 2026-07-07 14:30:00
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "0001_init_core_tables"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("user_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("provider", sa.Enum("google", name="auth_provider_enum"), nullable=False),
        sa.Column("social_id", sa.String(length=100), nullable=False),
        sa.Column("nickname", sa.String(length=50), nullable=False),
        sa.Column(
            "onboarding_status",
            sa.Enum("pending", "terms_agreed", "profile_required", "completed", name="onboarding_status_enum"),
            nullable=False,
        ),
        sa.Column("last_login_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("CURRENT_TIMESTAMP"),
            nullable=False,
        ),
        sa.PrimaryKeyConstraint("user_id"),
        sa.UniqueConstraint("provider", "social_id", name="uq_users_provider_social_id"),
    )

    op.create_table(
        "mission_templates",
        sa.Column("mission_template_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("mission_type", sa.Enum("meal", "exercise", "walking", "game", name="mission_type_enum"), nullable=False),
        sa.Column("title", sa.String(length=100), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("level", sa.Enum("easy", "normal", "hard", name="mission_level_enum"), nullable=False),
        sa.Column("display_order", sa.Integer(), nullable=False),
        sa.Column(
            "exercise_category",
            sa.Enum("warm_up", "seated", "standing", "cool_down", name="exercise_category_enum"),
            nullable=True,
        ),
        sa.Column(
            "activity_type",
            sa.Enum("walking", "chair_stand", "seated_exercise", "standing_exercise", "stretching", name="activity_type_enum"),
            nullable=True,
        ),
        sa.Column("default_target_value", sa.Integer(), nullable=False),
        sa.Column(
            "target_unit",
            sa.Enum("reps", "minutes", "steps", "count", "km", "sets", name="target_unit_enum"),
            nullable=False,
        ),
        sa.Column("estimated_intensity", sa.Enum("low", "moderate", "high", name="intensity_enum"), nullable=True),
        sa.Column("met_value", sa.Numeric(4, 2), nullable=True),
        sa.Column("evidence_message", sa.Text(), nullable=True),
        sa.Column("requires_safety_notice", sa.Boolean(), nullable=False),
        sa.Column("is_repeatable", sa.Boolean(), nullable=False),
        sa.Column("daily_count_limit", sa.Integer(), nullable=True),
        sa.Column("reward_points", sa.Integer(), nullable=False),
        sa.Column("requires_kidney_check", sa.Boolean(), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.PrimaryKeyConstraint("mission_template_id"),
    )

    op.create_table(
        "personalized_settings",
        sa.Column("setting_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("notification_enabled", sa.Boolean(), nullable=False),
        sa.Column("font_size", sa.Enum("small", "default", "large", name="font_size_enum"), nullable=False),
        sa.Column("sound_size", sa.Enum("small", "default", "large", name="sound_size_enum"), nullable=False),
        sa.Column("pet_type", sa.String(length=50), nullable=False),
        sa.Column("music_enabled", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("setting_id"),
        sa.UniqueConstraint("user_id"),
    )
    op.create_table(
        "terms_agreements",
        sa.Column("agreement_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("terms_type", sa.Enum("service", "privacy", "health_sensitive", "marketing", name="terms_type_enum"), nullable=False),
        sa.Column("is_required", sa.Boolean(), nullable=False),
        sa.Column("agreed", sa.Boolean(), nullable=False),
        sa.Column("version", sa.String(length=30), nullable=False),
        sa.Column("agreed_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("agreement_id"),
    )
    op.create_index("ix_terms_agreements_user_id", "terms_agreements", ["user_id"])

    op.create_table(
        "health_check_sessions",
        sa.Column("session_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("status", sa.Enum("started", "completed", "skipped", name="health_check_status_enum"), nullable=False),
        sa.Column("input_method", sa.Enum("form", "voice", "service_log", "sensor", "manual", name="health_input_method_enum"), nullable=False),
        sa.Column("raw_transcript", sa.Text(), nullable=True),
        sa.Column("has_estimated_value", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("session_id"),
    )
    op.create_index("ix_health_check_sessions_user_id", "health_check_sessions", ["user_id"])

    op.create_table(
        "health_profiles",
        sa.Column("profile_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("session_id", sa.BigInteger(), nullable=True),
        sa.Column("birth_date", sa.Date(), nullable=False),
        sa.Column("sex", sa.Enum("male", "female", name="sex_enum"), nullable=False),
        sa.Column("height_cm", sa.Numeric(5, 2), nullable=False),
        sa.Column("weight_kg", sa.Numeric(5, 2), nullable=False),
        sa.Column("bmi", sa.Numeric(4, 1), nullable=False),
        sa.Column("waist_cm", sa.Numeric(5, 2), nullable=True),
        sa.Column("walking_practice", sa.Boolean(), nullable=False),
        sa.Column("strength_exercise", sa.Boolean(), nullable=False),
        sa.Column("activity_input_source", sa.Enum("self_report", "service_log", name="activity_input_source_enum"), nullable=False),
        sa.Column("activity_window_days", sa.Integer(), nullable=True),
        sa.Column("kidney_status", sa.Enum("none", "kidney_disease", "dialysis", "unknown", name="kidney_status_enum"), nullable=False),
        sa.Column(
            "protein_restriction_status",
            sa.Enum("none", "restricted", "unknown", name="protein_restriction_status_enum"),
            nullable=False,
        ),
        sa.Column("protein_challenge_allowed", sa.Boolean(), nullable=False),
        sa.Column("input_method", sa.Enum("form", "voice", "service_log", "sensor", "manual", name="profile_input_method_enum"), nullable=False),
        sa.Column("has_estimated_value", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.ForeignKeyConstraint(["session_id"], ["health_check_sessions.session_id"]),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("profile_id"),
    )
    op.create_index("ix_health_profiles_session_id", "health_profiles", ["session_id"])
    op.create_index("ix_health_profiles_user_id", "health_profiles", ["user_id"])

    op.create_table(
        "physical_assessments",
        sa.Column("physical_assessment_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("session_id", sa.BigInteger(), nullable=True),
        sa.Column("assessment_type", sa.Enum("initial", "reassessment", name="assessment_type_enum"), nullable=False),
        sa.Column("chair_stand_5_time_sec", sa.Numeric(5, 2), nullable=True),
        sa.Column("chair_stand_skipped", sa.Boolean(), nullable=False),
        sa.Column("walk_6m_time_sec", sa.Numeric(5, 2), nullable=True),
        sa.Column("walk_6m_distance_m", sa.Numeric(4, 2), nullable=True),
        sa.Column("walk_6m_speed_mps", sa.Numeric(5, 2), nullable=True),
        sa.Column("walk_6m_skipped", sa.Boolean(), nullable=False),
        sa.Column("pain_reported", sa.Boolean(), nullable=False),
        sa.Column("dizziness_reported", sa.Boolean(), nullable=False),
        sa.Column("used_for_level_setting", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.ForeignKeyConstraint(["session_id"], ["health_check_sessions.session_id"]),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("physical_assessment_id"),
    )
    op.create_index("ix_physical_assessments_session_id", "physical_assessments", ["session_id"])
    op.create_index("ix_physical_assessments_user_id", "physical_assessments", ["user_id"])

    op.create_table(
        "user_activity_profiles",
        sa.Column("activity_profile_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("current_level", sa.Enum("easy", "normal", "hard", name="activity_level_enum"), nullable=False),
        sa.Column(
            "level_reason",
            sa.Enum("rule", "initial_test", "reassessment", "user_selected", "default", name="level_reason_enum"),
            nullable=False,
        ),
        sa.Column("physical_assessment_id", sa.BigInteger(), nullable=True),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.ForeignKeyConstraint(["physical_assessment_id"], ["physical_assessments.physical_assessment_id"]),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("activity_profile_id"),
        sa.UniqueConstraint("user_id"),
    )
    op.create_index("ix_user_activity_profiles_physical_assessment_id", "user_activity_profiles", ["physical_assessment_id"])
    op.create_table(
        "risk_predictions",
        sa.Column("prediction_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("profile_id", sa.BigInteger(), nullable=False),
        sa.Column("model_version", sa.String(length=50), nullable=False),
        sa.Column("model_variant", sa.Enum("minimal", "with_waist", "rule_based_scaffold", name="model_variant_enum"), nullable=False),
        sa.Column("internal_risk_score", sa.Numeric(6, 3), nullable=False),
        sa.Column("internal_risk_level", sa.Enum("low", "medium", "high", name="risk_level_enum"), nullable=False),
        sa.Column("input_snapshot", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.ForeignKeyConstraint(["profile_id"], ["health_profiles.profile_id"]),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("prediction_id"),
    )
    op.create_index("ix_risk_predictions_profile_id", "risk_predictions", ["profile_id"])
    op.create_index("ix_risk_predictions_user_id", "risk_predictions", ["user_id"])

    op.create_table(
        "mission_logs",
        sa.Column("mission_log_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("mission_template_id", sa.BigInteger(), nullable=False),
        sa.Column("mission_type", sa.Enum("meal", "exercise", "walking", "game", name="mission_log_type_enum"), nullable=False),
        sa.Column("status", sa.Enum("in_progress", "completed", "skipped", name="mission_status_enum"), nullable=False),
        sa.Column("performed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("actual_value", sa.Numeric(8, 2), nullable=True),
        sa.Column("target_value", sa.Numeric(8, 2), nullable=True),
        sa.Column("target_unit", sa.Enum("reps", "minutes", "steps", "count", "km", "sets", name="mission_log_target_unit_enum"), nullable=True),
        sa.Column("success", sa.Boolean(), nullable=False),
        sa.Column("input_method", sa.Enum("form", "voice", "service_log", "sensor", "manual", name="mission_input_method_enum"), nullable=True),
        sa.Column("manual_override", sa.Boolean(), nullable=False),
        sa.Column("safety_notice_confirmed", sa.Boolean(), nullable=False),
        sa.Column("safety_notice_confirmed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("perceived_difficulty", sa.Enum("easy", "just_right", "hard", name="perceived_difficulty_enum"), nullable=True),
        sa.Column("pain_reported", sa.Boolean(), nullable=False),
        sa.Column("dizziness_reported", sa.Boolean(), nullable=False),
        sa.Column("counted_for_daily", sa.Boolean(), nullable=False),
        sa.Column("earned_points", sa.Integer(), nullable=False),
        sa.Column("created_on_device_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("synced_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.ForeignKeyConstraint(["mission_template_id"], ["mission_templates.mission_template_id"]),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("mission_log_id"),
    )
    op.create_index("ix_mission_logs_mission_template_id", "mission_logs", ["mission_template_id"])
    op.create_index("ix_mission_logs_user_id", "mission_logs", ["user_id"])

    op.create_table(
        "daily_activity_summaries",
        sa.Column("summary_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("summary_date", sa.Date(), nullable=False),
        sa.Column("counted_mission_count", sa.Integer(), nullable=False),
        sa.Column("meal_counted", sa.Boolean(), nullable=False),
        sa.Column("exercise_count", sa.Integer(), nullable=False),
        sa.Column("walking_count", sa.Integer(), nullable=False),
        sa.Column("game_count", sa.Integer(), nullable=False),
        sa.Column("daily_result", sa.Enum("none", "success", "great_success", name="daily_result_enum"), nullable=False),
        sa.Column("earned_points", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("summary_id"),
        sa.UniqueConstraint("user_id", "summary_date", name="uq_daily_activity_summaries_user_date"),
    )
    op.create_index("ix_daily_activity_summaries_summary_date", "daily_activity_summaries", ["summary_date"])
    op.create_index("ix_daily_activity_summaries_user_id", "daily_activity_summaries", ["user_id"])

    op.create_table(
        "point_balances",
        sa.Column("point_balance_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("user_id", sa.BigInteger(), nullable=False),
        sa.Column("current_points", sa.Integer(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"]),
        sa.PrimaryKeyConstraint("point_balance_id"),
        sa.UniqueConstraint("user_id"),
    )
    op.create_table(
        "sensor_sessions",
        sa.Column("sensor_session_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("mission_log_id", sa.BigInteger(), nullable=False),
        sa.Column("sensor_type", sa.Enum("accelerometer", "gyroscope", "step_counter", name="sensor_type_enum"), nullable=False),
        sa.Column("detected_count", sa.Integer(), nullable=True),
        sa.Column("duration_sec", sa.Integer(), nullable=True),
        sa.Column("motion_score", sa.Numeric(5, 2), nullable=True),
        sa.Column("recognition_status", sa.Enum("recognized", "low_confidence", "failed", name="recognition_status_enum"), nullable=False),
        sa.Column("raw_summary", sa.JSON(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.ForeignKeyConstraint(["mission_log_id"], ["mission_logs.mission_log_id"]),
        sa.PrimaryKeyConstraint("sensor_session_id"),
    )
    op.create_index("ix_sensor_sessions_mission_log_id", "sensor_sessions", ["mission_log_id"])

    op.create_table(
        "meal_logs",
        sa.Column("meal_log_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("mission_log_id", sa.BigInteger(), nullable=False),
        sa.Column("meal_date", sa.Date(), nullable=False),
        sa.Column("protein_foods", sa.JSON(), nullable=False),
        sa.Column("protein_meal_count", sa.Integer(), nullable=False),
        sa.Column("raw_text", sa.Text(), nullable=True),
        sa.Column("counted_for_daily", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.ForeignKeyConstraint(["mission_log_id"], ["mission_logs.mission_log_id"]),
        sa.PrimaryKeyConstraint("meal_log_id"),
        sa.UniqueConstraint("mission_log_id"),
    )
    op.create_index("ix_meal_logs_meal_date", "meal_logs", ["meal_date"])
    op.create_table(
        "physical_activity_logs",
        sa.Column("physical_activity_log_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("mission_log_id", sa.BigInteger(), nullable=False),
        sa.Column("activity_date", sa.Date(), nullable=False),
        sa.Column(
            "activity_type",
            sa.Enum("walking", "chair_stand", "seated_exercise", "standing_exercise", "stretching", name="physical_activity_type_enum"),
            nullable=False,
        ),
        sa.Column("intensity", sa.Enum("low", "moderate", "high", name="physical_activity_intensity_enum"), nullable=True),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("ended_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("duration_min", sa.Numeric(6, 2), nullable=True),
        sa.Column("steps", sa.Integer(), nullable=True),
        sa.Column("distance_km", sa.Numeric(6, 3), nullable=True),
        sa.Column("reps", sa.Integer(), nullable=True),
        sa.Column("sets", sa.Integer(), nullable=True),
        sa.Column("met_value", sa.Numeric(4, 2), nullable=True),
        sa.Column("moderate_equivalent_min", sa.Numeric(6, 2), nullable=True),
        sa.Column("source", sa.Enum("sensor", "manual", name="activity_source_enum"), nullable=False),
        sa.Column("sync_status", sa.Enum("synced", "pending", "recovered", name="sync_status_enum"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.ForeignKeyConstraint(["mission_log_id"], ["mission_logs.mission_log_id"]),
        sa.PrimaryKeyConstraint("physical_activity_log_id"),
        sa.UniqueConstraint("mission_log_id"),
    )
    op.create_index("ix_physical_activity_logs_activity_date", "physical_activity_logs", ["activity_date"])
    op.create_table(
        "game_logs",
        sa.Column("game_log_id", sa.BigInteger(), autoincrement=True, nullable=False),
        sa.Column("mission_log_id", sa.BigInteger(), nullable=False),
        sa.Column("game_type", sa.Enum("card_match", name="game_type_enum"), nullable=False),
        sa.Column("score", sa.Integer(), nullable=True),
        sa.Column("duration_sec", sa.Integer(), nullable=True),
        sa.Column("success_count", sa.Integer(), nullable=True),
        sa.Column("mistake_count", sa.Integer(), nullable=True),
        sa.Column("completed", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("CURRENT_TIMESTAMP"), nullable=False),
        sa.ForeignKeyConstraint(["mission_log_id"], ["mission_logs.mission_log_id"]),
        sa.PrimaryKeyConstraint("game_log_id"),
        sa.UniqueConstraint("mission_log_id"),
    )

def downgrade() -> None:
    op.drop_table("game_logs")
    op.drop_table("physical_activity_logs")
    op.drop_table("meal_logs")
    op.drop_table("sensor_sessions")
    op.drop_table("point_balances")
    op.drop_table("daily_activity_summaries")
    op.drop_table("mission_logs")
    op.drop_table("risk_predictions")
    op.drop_table("user_activity_profiles")
    op.drop_table("physical_assessments")
    op.drop_table("health_profiles")
    op.drop_table("health_check_sessions")
    op.drop_table("terms_agreements")
    op.drop_table("personalized_settings")
    op.drop_table("mission_templates")
    op.drop_table("users")

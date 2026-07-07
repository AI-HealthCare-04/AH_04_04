from datetime import date, datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import JSON, BigInteger, Boolean, Date, DateTime, Enum, ForeignKey, Integer, Numeric, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TimestampMixin
from app.models.enums import (
    ActivityLevel,
    ActivitySource,
    ActivityType,
    ExerciseCategory,
    GameType,
    InputMethod,
    Intensity,
    MissionStatus,
    MissionType,
    PerceivedDifficulty,
    RecognitionStatus,
    SensorType,
    SyncStatus,
    TargetUnit,
    enum_values,
)


class MissionTemplate(Base, TimestampMixin):
    __tablename__ = "mission_templates"

    mission_template_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    mission_type: Mapped[MissionType] = mapped_column(
        Enum(MissionType, values_callable=enum_values, name="mission_type_enum"),
        nullable=False,
    )
    title: Mapped[str] = mapped_column(String(100), nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    level: Mapped[ActivityLevel] = mapped_column(
        Enum(ActivityLevel, values_callable=enum_values, name="mission_level_enum"),
        nullable=False,
    )
    display_order: Mapped[int] = mapped_column(Integer, nullable=False)
    exercise_category: Mapped[ExerciseCategory | None] = mapped_column(
        Enum(ExerciseCategory, values_callable=enum_values, name="exercise_category_enum"),
        nullable=True,
    )
    activity_type: Mapped[ActivityType | None] = mapped_column(
        Enum(ActivityType, values_callable=enum_values, name="activity_type_enum"),
        nullable=True,
    )
    default_target_value: Mapped[int] = mapped_column(Integer, nullable=False)
    target_unit: Mapped[TargetUnit] = mapped_column(
        Enum(TargetUnit, values_callable=enum_values, name="target_unit_enum"),
        nullable=False,
    )
    estimated_intensity: Mapped[Intensity | None] = mapped_column(
        Enum(Intensity, values_callable=enum_values, name="intensity_enum"),
        nullable=True,
    )
    met_value: Mapped[Decimal | None] = mapped_column(Numeric(4, 2), nullable=True)
    evidence_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    requires_safety_notice: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    is_repeatable: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    daily_count_limit: Mapped[int | None] = mapped_column(Integer, nullable=True)
    reward_points: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    requires_kidney_check: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)


class MissionLog(Base):
    __tablename__ = "mission_logs"

    mission_log_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.user_id"), nullable=False, index=True)
    mission_template_id: Mapped[int] = mapped_column(
        ForeignKey("mission_templates.mission_template_id"),
        nullable=False,
        index=True,
    )
    mission_type: Mapped[MissionType] = mapped_column(
        Enum(MissionType, values_callable=enum_values, name="mission_log_type_enum"),
        nullable=False,
    )
    status: Mapped[MissionStatus] = mapped_column(
        Enum(MissionStatus, values_callable=enum_values, name="mission_status_enum"),
        nullable=False,
    )
    performed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    actual_value: Mapped[Decimal | None] = mapped_column(Numeric(8, 2), nullable=True)
    target_value: Mapped[Decimal | None] = mapped_column(Numeric(8, 2), nullable=True)
    target_unit: Mapped[TargetUnit | None] = mapped_column(
        Enum(TargetUnit, values_callable=enum_values, name="mission_log_target_unit_enum"),
        nullable=True,
    )
    success: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    input_method: Mapped[InputMethod | None] = mapped_column(
        Enum(InputMethod, values_callable=enum_values, name="mission_input_method_enum"),
        nullable=True,
    )
    manual_override: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    safety_notice_confirmed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    safety_notice_confirmed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    perceived_difficulty: Mapped[PerceivedDifficulty | None] = mapped_column(
        Enum(PerceivedDifficulty, values_callable=enum_values, name="perceived_difficulty_enum"),
        nullable=True,
    )
    pain_reported: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    dizziness_reported: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    counted_for_daily: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    earned_points: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_on_device_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    synced_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class SensorSession(Base):
    __tablename__ = "sensor_sessions"

    sensor_session_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    mission_log_id: Mapped[int] = mapped_column(ForeignKey("mission_logs.mission_log_id"), nullable=False, index=True)
    sensor_type: Mapped[SensorType] = mapped_column(
        Enum(SensorType, values_callable=enum_values, name="sensor_type_enum"),
        nullable=False,
    )
    detected_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    duration_sec: Mapped[int | None] = mapped_column(Integer, nullable=True)
    motion_score: Mapped[Decimal | None] = mapped_column(Numeric(5, 2), nullable=True)
    recognition_status: Mapped[RecognitionStatus] = mapped_column(
        Enum(RecognitionStatus, values_callable=enum_values, name="recognition_status_enum"),
        nullable=False,
    )
    raw_summary: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class MealLog(Base):
    __tablename__ = "meal_logs"

    meal_log_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    mission_log_id: Mapped[int] = mapped_column(
        ForeignKey("mission_logs.mission_log_id"),
        nullable=False,
        unique=True,
    )
    meal_date: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    protein_foods: Mapped[list[str]] = mapped_column(JSON, nullable=False)
    protein_meal_count: Mapped[int] = mapped_column(Integer, nullable=False)
    raw_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    counted_for_daily: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class PhysicalActivityLog(Base):
    __tablename__ = "physical_activity_logs"

    physical_activity_log_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    mission_log_id: Mapped[int] = mapped_column(
        ForeignKey("mission_logs.mission_log_id"),
        nullable=False,
        unique=True,
    )
    activity_date: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    activity_type: Mapped[ActivityType] = mapped_column(
        Enum(ActivityType, values_callable=enum_values, name="physical_activity_type_enum"),
        nullable=False,
    )
    intensity: Mapped[Intensity | None] = mapped_column(
        Enum(Intensity, values_callable=enum_values, name="physical_activity_intensity_enum"),
        nullable=True,
    )
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    duration_min: Mapped[Decimal | None] = mapped_column(Numeric(6, 2), nullable=True)
    steps: Mapped[int | None] = mapped_column(Integer, nullable=True)
    distance_km: Mapped[Decimal | None] = mapped_column(Numeric(6, 3), nullable=True)
    reps: Mapped[int | None] = mapped_column(Integer, nullable=True)
    sets: Mapped[int | None] = mapped_column(Integer, nullable=True)
    met_value: Mapped[Decimal | None] = mapped_column(Numeric(4, 2), nullable=True)
    moderate_equivalent_min: Mapped[Decimal | None] = mapped_column(Numeric(6, 2), nullable=True)
    source: Mapped[ActivitySource] = mapped_column(
        Enum(ActivitySource, values_callable=enum_values, name="activity_source_enum"),
        nullable=False,
    )
    sync_status: Mapped[SyncStatus] = mapped_column(
        Enum(SyncStatus, values_callable=enum_values, name="sync_status_enum"),
        nullable=False,
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class GameLog(Base):
    __tablename__ = "game_logs"

    game_log_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    mission_log_id: Mapped[int] = mapped_column(
        ForeignKey("mission_logs.mission_log_id"),
        nullable=False,
        unique=True,
    )
    game_type: Mapped[GameType] = mapped_column(
        Enum(GameType, values_callable=enum_values, name="game_type_enum"),
        nullable=False,
    )
    score: Mapped[int | None] = mapped_column(Integer, nullable=True)
    duration_sec: Mapped[int | None] = mapped_column(Integer, nullable=True)
    success_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    mistake_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    completed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

from datetime import date, datetime
from decimal import Decimal

from sqlalchemy import BigInteger, Boolean, Date, DateTime, Enum, ForeignKey, Integer, Numeric, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base
from app.models.enums import (
    ActivityInputSource,
    AssessmentType,
    HealthCheckStatus,
    InputMethod,
    KidneyStatus,
    ProteinRestrictionStatus,
    Sex,
    enum_values,
)


class HealthCheckSession(Base):
    __tablename__ = "health_check_sessions"

    session_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.user_id"), nullable=False, index=True)
    status: Mapped[HealthCheckStatus] = mapped_column(
        Enum(HealthCheckStatus, values_callable=enum_values, name="health_check_status_enum"),
        nullable=False,
    )
    input_method: Mapped[InputMethod] = mapped_column(
        Enum(InputMethod, values_callable=enum_values, name="health_input_method_enum"),
        nullable=False,
    )
    has_estimated_value: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class HealthProfile(Base):
    __tablename__ = "health_profiles"

    profile_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.user_id"), nullable=False, index=True)
    session_id: Mapped[int | None] = mapped_column(
        ForeignKey("health_check_sessions.session_id"),
        nullable=True,
        index=True,
    )
    birth_date: Mapped[date] = mapped_column(Date, nullable=False)
    sex: Mapped[Sex] = mapped_column(Enum(Sex, values_callable=enum_values, name="sex_enum"), nullable=False)
    height_cm: Mapped[Decimal] = mapped_column(Numeric(5, 2), nullable=False)
    weight_kg: Mapped[Decimal] = mapped_column(Numeric(5, 2), nullable=False)
    bmi: Mapped[Decimal] = mapped_column(Numeric(4, 1), nullable=False)
    waist_cm: Mapped[Decimal | None] = mapped_column(Numeric(5, 2), nullable=True)
    walking_practice: Mapped[bool] = mapped_column(Boolean, nullable=False)
    strength_exercise: Mapped[bool] = mapped_column(Boolean, nullable=False)
    activity_input_source: Mapped[ActivityInputSource] = mapped_column(
        Enum(ActivityInputSource, values_callable=enum_values, name="activity_input_source_enum"),
        nullable=False,
    )
    activity_window_days: Mapped[int | None] = mapped_column(Integer, nullable=True)
    kidney_status: Mapped[KidneyStatus] = mapped_column(
        Enum(KidneyStatus, values_callable=enum_values, name="kidney_status_enum"),
        nullable=False,
    )
    protein_restriction_status: Mapped[ProteinRestrictionStatus] = mapped_column(
        Enum(ProteinRestrictionStatus, values_callable=enum_values, name="protein_restriction_status_enum"),
        nullable=False,
    )
    protein_challenge_allowed: Mapped[bool] = mapped_column(Boolean, nullable=False)
    input_method: Mapped[InputMethod] = mapped_column(
        Enum(InputMethod, values_callable=enum_values, name="profile_input_method_enum"),
        nullable=False,
    )
    has_estimated_value: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)


class PhysicalAssessment(Base):
    __tablename__ = "physical_assessments"

    physical_assessment_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.user_id"), nullable=False, index=True)
    session_id: Mapped[int | None] = mapped_column(
        ForeignKey("health_check_sessions.session_id"),
        nullable=True,
        index=True,
    )
    assessment_type: Mapped[AssessmentType] = mapped_column(
        Enum(AssessmentType, values_callable=enum_values, name="assessment_type_enum"),
        nullable=False,
    )
    chair_stand_5_time_sec: Mapped[Decimal | None] = mapped_column(Numeric(5, 2), nullable=True)
    chair_stand_skipped: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    walk_6m_time_sec: Mapped[Decimal | None] = mapped_column(Numeric(5, 2), nullable=True)
    walk_6m_distance_m: Mapped[Decimal | None] = mapped_column(Numeric(4, 2), nullable=True)
    walk_6m_speed_mps: Mapped[Decimal | None] = mapped_column(Numeric(5, 2), nullable=True)
    walk_6m_skipped: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    pain_reported: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    dizziness_reported: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    used_for_level_setting: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)

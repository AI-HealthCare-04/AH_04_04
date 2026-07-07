from datetime import datetime

from sqlalchemy import BigInteger, DateTime, Enum, ForeignKey, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base
from app.models.enums import ActivityLevel, LevelReason, enum_values


class UserActivityProfile(Base):
    __tablename__ = "user_activity_profiles"

    activity_profile_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.user_id"), nullable=False, unique=True)
    current_level: Mapped[ActivityLevel] = mapped_column(
        Enum(ActivityLevel, values_callable=enum_values, name="activity_level_enum"),
        nullable=False,
    )
    level_reason: Mapped[LevelReason] = mapped_column(
        Enum(LevelReason, values_callable=enum_values, name="level_reason_enum"),
        nullable=False,
    )
    physical_assessment_id: Mapped[int | None] = mapped_column(
        ForeignKey("physical_assessments.physical_assessment_id"),
        nullable=True,
        index=True,
    )
    started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )

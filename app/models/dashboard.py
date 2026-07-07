from datetime import date, datetime

from sqlalchemy import BigInteger, Boolean, Date, DateTime, Enum, ForeignKey, Integer, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base
from app.models.enums import DailyResult, enum_values


class DailyActivitySummary(Base):
    __tablename__ = "daily_activity_summaries"
    __table_args__ = (UniqueConstraint("user_id", "summary_date", name="uq_daily_activity_summaries_user_date"),)

    summary_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.user_id"), nullable=False, index=True)
    summary_date: Mapped[date] = mapped_column(Date, nullable=False, index=True)
    counted_mission_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    meal_counted: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    exercise_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    walking_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    game_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    daily_result: Mapped[DailyResult] = mapped_column(
        Enum(DailyResult, values_callable=enum_values, name="daily_result_enum"),
        nullable=False,
        default=DailyResult.NONE,
    )
    earned_points: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class PointBalance(Base):
    __tablename__ = "point_balances"

    point_balance_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.user_id"), nullable=False, unique=True)
    current_points: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

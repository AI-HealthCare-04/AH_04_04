from datetime import datetime

from sqlalchemy import BigInteger, DateTime, Enum, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, TimestampMixin
from app.models.enums import AuthProvider, OnboardingStatus, enum_values


class User(Base, TimestampMixin):
    __tablename__ = "users"
    __table_args__ = (UniqueConstraint("provider", "social_id", name="uq_users_provider_social_id"),)

    user_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    provider: Mapped[AuthProvider] = mapped_column(
        Enum(AuthProvider, values_callable=enum_values, name="auth_provider_enum"),
        nullable=False,
    )
    social_id: Mapped[str] = mapped_column(String(100), nullable=False)
    nickname: Mapped[str] = mapped_column(String(50), nullable=False)
    onboarding_status: Mapped[OnboardingStatus] = mapped_column(
        Enum(OnboardingStatus, values_callable=enum_values, name="onboarding_status_enum"),
        nullable=False,
        default=OnboardingStatus.PENDING,
    )
    last_login_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    @property
    def id(self) -> int:
        return self.user_id

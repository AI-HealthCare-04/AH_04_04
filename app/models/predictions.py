from datetime import datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import JSON, BigInteger, DateTime, Enum, ForeignKey, Index, Numeric, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base
from app.models.enums import ModelVariant, RiskLevel, enum_values


class RiskPrediction(Base):
    __tablename__ = "risk_predictions"
    __table_args__ = (Index("ix_risk_predictions_user_created_id", "user_id", "created_at", "prediction_id"),)

    prediction_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.user_id"), nullable=False)
    profile_id: Mapped[int] = mapped_column(ForeignKey("health_profiles.profile_id"), nullable=False, index=True)
    model_version: Mapped[str] = mapped_column(String(100), nullable=False)
    model_variant: Mapped[ModelVariant] = mapped_column(
        Enum(ModelVariant, values_callable=enum_values, name="model_variant_enum"),
        nullable=False,
    )
    internal_risk_score: Mapped[Decimal] = mapped_column(Numeric(6, 3), nullable=False)
    internal_risk_level: Mapped[RiskLevel] = mapped_column(
        Enum(RiskLevel, values_callable=enum_values, name="risk_level_enum"),
        nullable=False,
    )
    input_snapshot: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)

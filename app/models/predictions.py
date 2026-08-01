from datetime import datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    CheckConstraint,
    DateTime,
    Enum,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    String,
    func,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base
from app.models.enums import FeedbackReason, FeedbackResponse, ModelVariant, RiskLevel, enum_values


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
    muscle_score: Mapped[int | None] = mapped_column(Integer, nullable=True)
    score_band: Mapped[str | None] = mapped_column(String(20), nullable=True)
    score_p_low: Mapped[Decimal | None] = mapped_column(Numeric(8, 5), nullable=True)
    score_p_high: Mapped[Decimal | None] = mapped_column(Numeric(8, 5), nullable=True)
    score_cohort_age: Mapped[str | None] = mapped_column(String(4), nullable=True)
    score_cohort_version: Mapped[str | None] = mapped_column(String(50), nullable=True)
    input_snapshot: Mapped[dict[str, Any]] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)


class PredictionFeedback(Base):
    """예측 결과에 대한 사용자 체감 피드백(#357 옵션 B).

    사용자가 점수를 본 뒤 "지금 내 상태와 비슷한가"에 답한 **주관적 체감·수용도**다.
    모델 정답 라벨이 아니며 재학습에 직접 투입하지 않는다 — 불일치가 높은 구간을 찾아
    추가 검증하는 후보 신호로만 쓴다(지영 리뷰, 이슈 #357).

    입력 피처·점수·모델 버전은 저장하지 않는다: prediction_id 로 risk_predictions 에
    이미 있는 스냅샷과 연결되므로 복제하면 정합성만 깨진다(지영 리뷰).
    prediction_id UNIQUE 가 '예측 1건당 응답 1회'를 DB 차원에서 보장하고,
    라우터의 멱등 PUT 이 모바일 재시도를 안전하게 만든다.
    is_test 는 시연·QA 응답 표식 — 심사 자료의 실제 사용자 N 에 섞이지 않도록
    집계 스크립트가 제외한다(기본 false, 시연 계정은 운영 SQL 로 마킹).
    """

    __tablename__ = "prediction_feedbacks"
    __table_args__ = (
        # reason 은 'different'의 불일치 사유(리뷰 반영) — API 검증을 우회하는 운영 SQL·후속 코드
        #   경로에서도 similar/unsure 에 사유가 붙지 않게 DB 가 직접 막는다(MySQL 8.0.16+ CHECK 강제).
        CheckConstraint(
            "reason IS NULL OR response = 'different'",
            name="ck_prediction_feedbacks_reason_scope",
        ),
    )

    feedback_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    prediction_id: Mapped[int] = mapped_column(
        ForeignKey("risk_predictions.prediction_id"), nullable=False, unique=True
    )
    response: Mapped[FeedbackResponse] = mapped_column(
        Enum(FeedbackResponse, values_callable=enum_values, name="feedback_response_enum"),
        nullable=False,
    )
    reason: Mapped[FeedbackReason | None] = mapped_column(
        Enum(FeedbackReason, values_callable=enum_values, name="feedback_reason_enum"),
        nullable=True,
    )
    is_test: Mapped[bool] = mapped_column(Boolean, server_default=text("0"), default=False, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)

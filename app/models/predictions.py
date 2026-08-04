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
    # 또래 분포 조회 키의 성별. **모델 인코딩과 같은 값만 넣는다 — male=1, female=2**
    #   (app/ml/predictor.py `_normalize_sex`). 코호트표 키가 이 값이라, 0 같은 다른 숫자가 들어가면
    #   조회가 영구히 실패한다. 예측 시점 값을 고정 저장한다 — 프로필을 고쳐도 확률을 만든 모델·입력과
    #   코호트가 어긋나지 않아야 한다(리뷰 #301). 이전에는 input_snapshot 에서 읽었으나 원본 보관을
    #   없애며(#408) 조회에 실제로 필요한 이 값만 컬럼으로 승격했다.
    score_cohort_sex: Mapped[int | None] = mapped_column(Integer, nullable=True)
    score_cohort_version: Mapped[str | None] = mapped_column(String(50), nullable=True)
    # ⚠️ 원본 입력 스냅샷은 더 이상 저장하지 않는다(#408 — 1차 검토 피드백: 서버 보관 최소화).
    #   예측 1건마다 키·몸무게·허리둘레·신장상태 등 건강 원본이 JSON 으로 복제 저장되고 있었고,
    #   화면·추이가 실제로 쓰는 것은 결과 점수와 코호트 키뿐이었다(위 컬럼들).
    #   신규 예측은 NULL 로 남기고 기존 행도 마이그레이션 0020 에서 비웠다.
    input_snapshot: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    # 근육 점수 SHAP 기여도(#406)는 **저장하지 않는다.** 파생값도 `x = mean + std × (effect / -coef)` 로
    #   허리둘레 원본이 역산돼(#406 리뷰 P1, round(,4)로 ±0.005cm 사실상 무손실) #408 최소화를 무력화한다.
    #   대신 create·재계산 응답에만 실어 한 번 내려주고(app/services/risk_prediction._contributions),
    #   앱이 prediction_id 기준 로컬 캐시로 대시보드 막대를 그린다. 그래서 이 모델에 컬럼이 없다.
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

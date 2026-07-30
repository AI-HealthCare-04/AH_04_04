from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, Field

from app.dtos.base import KstDatetime
from app.models.enums import ActivityInputSource


class RiskPredictionCreateRequest(BaseModel):
    profile_id: int


class RiskPredictionReassessRequest(BaseModel):
    activity_window_days: Literal[7, 14]


class CareStage(StrEnum):
    GOOD = "good"
    MAINTAIN = "maintain"
    ACTION_NEEDED = "action_needed"


class RiskComparisonStatus(StrEnum):
    BASELINE = "baseline"
    COMPARABLE = "comparable"
    MODEL_CHANGED = "model_changed"


class RiskPredictionResponse(BaseModel):
    prediction_id: int
    profile_id: int
    model_variant: str
    risk_score: float = Field(ge=0, le=1)
    care_stage: CareStage
    display_message: str
    disclaimer: str = "본 결과는 참고용이며 의학적 진단이 아닙니다."
    # 근육 건강 점수(기록 탭 §3). 코호트 분위수표 미도착·65세 미만 등에서는 null → 앱이 "준비 중"/§3.3 카드.
    #   (model_version 은 기존 비노출 계약 유지 — cohort_version 만 버전 관리로 동봉.)
    score: int | None = Field(default=None, ge=0, le=100)
    score_band: str | None = None  # good | maintain | caution
    cohort_version: str | None = None


class RiskPredictionCreateResponse(RiskPredictionResponse):
    onboarding_status: str


class RiskPredictionReassessResponse(BaseModel):
    profile_id: int
    prediction_id: int
    risk_score: float = Field(ge=0, le=1)
    care_stage: CareStage
    display_message: str
    disclaimer: str = "본 결과는 참고용이며 의학적 진단이 아닙니다."
    activity_input_source: ActivityInputSource = ActivityInputSource.SERVICE_LOG


class RiskPredictionHistoryItem(BaseModel):
    # 연속 위험도는 공개하되 내부 등급·모델 식별자는 비노출한다.
    # 모델 버전 비교는 서버가 comparison_status로 추상화한다.
    prediction_id: int
    created_at: KstDatetime
    risk_score: float = Field(ge=0, le=1)
    change_percentage_points: float | None = Field(ge=-100, le=100)
    comparison_status: RiskComparisonStatus
    # 기존 Android 계약 호환용. 연속형 화면 전환 후 제거 또는 내부 한정 예정이다.
    care_stage: CareStage
    # 기록 탭 점수 추이(§3.2) — y를 점수로 그린다. 표 미도착/65세 미만이면 null.
    score: int | None = Field(default=None, ge=0, le=100)
    score_band: str | None = None


class RiskPredictionHistoryResponse(BaseModel):
    predictions: list[RiskPredictionHistoryItem]
    cohort_version: str | None = None

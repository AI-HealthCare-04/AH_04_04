from enum import StrEnum
from typing import Literal

from pydantic import BaseModel

from app.dtos.base import KstDatetime
from app.models.enums import ActivityInputSource, ModelVariant


class RiskPredictionCreateRequest(BaseModel):
    profile_id: int


class RiskPredictionReassessRequest(BaseModel):
    activity_window_days: Literal[7, 14]


class CareStage(StrEnum):
    GOOD = "good"
    MAINTAIN = "maintain"
    ACTION_NEEDED = "action_needed"


class RiskPredictionResponse(BaseModel):
    prediction_id: int
    profile_id: int
    model_variant: str
    care_stage: CareStage
    display_message: str
    disclaimer: str = "본 결과는 참고용이며 의학적 진단이 아닙니다."


class RiskPredictionCreateResponse(RiskPredictionResponse):
    onboarding_status: str


class RiskPredictionReassessResponse(BaseModel):
    profile_id: int
    prediction_id: int
    care_stage: CareStage
    display_message: str
    disclaimer: str = "본 결과는 참고용이며 의학적 진단이 아닙니다."
    activity_input_source: ActivityInputSource = ActivityInputSource.SERVICE_LOG


class RiskPredictionHistoryItem(BaseModel):
    # ⚠️ 비노출 계약(#57): 내부 risk_level·risk_score 는 이력 응답에 절대 넣지 않는다.
    #    `_13 나의 기록`은 care_stage(순화 등급)만 표시용으로 노출한다.
    prediction_id: int
    created_at: KstDatetime
    care_stage: CareStage
    model_variant: ModelVariant


class RiskPredictionHistoryResponse(BaseModel):
    predictions: list[RiskPredictionHistoryItem]

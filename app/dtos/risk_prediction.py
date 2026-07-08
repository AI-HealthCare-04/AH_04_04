from enum import StrEnum

from pydantic import BaseModel


class RiskPredictionCreateRequest(BaseModel):
    profile_id: int


class CareStage(StrEnum):
    GOOD = "good"
    MAINTAIN = "maintain"
    ACTION_NEEDED = "action_needed"


class RiskPredictionResponse(BaseModel):
    prediction_id: int
    care_stage: CareStage
    display_message: str
    disclaimer: str = "본 결과는 참고용이며 의학적 진단이 아닙니다."

from pydantic import BaseModel


class RiskPredictionCreateRequest(BaseModel):
    profile_id: int


class RiskPredictionResponse(BaseModel):
    prediction_id: int | None = None
    care_stage: str
    display_message: str
    disclaimer: str = "본 결과는 참고용이며 의학적 진단이 아닙니다."

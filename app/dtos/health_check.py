from pydantic import BaseModel

from app.dtos.base import BaseSerializerModel, KstDatetime
from app.models.enums import HealthCheckStatus, InputMethod


class HealthCheckSessionCreateRequest(BaseModel):
    input_method: InputMethod = InputMethod.FORM


class HealthCheckSessionResponse(BaseSerializerModel):
    session_id: int
    status: HealthCheckStatus
    input_method: InputMethod
    has_estimated_value: bool
    created_at: KstDatetime
    completed_at: KstDatetime | None


class HealthCheckSkipResponse(BaseModel):
    # 난이도 폐기(#428): 건너뛰기는 온보딩 상태 전이만 담당한다 — 기본 난이도 생성·반환 제거.
    session_id: int
    status: HealthCheckStatus
    onboarding_status: str

from pydantic import BaseModel

from app.dtos.activity_profile import ActivityProfileResponse
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
    session_id: int
    status: HealthCheckStatus
    onboarding_status: str
    activity_profile: ActivityProfileResponse

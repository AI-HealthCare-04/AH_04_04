from datetime import datetime

from pydantic import BaseModel

from app.dtos.activity_profile import ActivityProfileResponse
from app.dtos.base import BaseSerializerModel
from app.models.enums import HealthCheckStatus, InputMethod


class HealthCheckSessionCreateRequest(BaseModel):
    input_method: InputMethod = InputMethod.FORM


# 음성 입력의 요청/응답 계약은 app/dtos/voice_parse.py (VoiceParseRequest/Response)를 사용한다.
# (POST /health-check/sessions/{id}/voice — field+raw_transcript → field/value/needs_confirmation)


class HealthCheckSessionResponse(BaseSerializerModel):
    session_id: int
    status: HealthCheckStatus
    input_method: InputMethod
    raw_transcript: str | None
    has_estimated_value: bool
    created_at: datetime
    completed_at: datetime | None


class HealthCheckSkipResponse(BaseModel):
    session_id: int
    status: HealthCheckStatus
    onboarding_status: str
    activity_profile: ActivityProfileResponse

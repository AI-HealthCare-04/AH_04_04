from datetime import datetime

from pydantic import BaseModel, Field, field_validator

from app.dtos.activity_profile import ActivityProfileResponse
from app.dtos.base import BaseSerializerModel
from app.models.enums import HealthCheckStatus, InputMethod


class HealthCheckSessionCreateRequest(BaseModel):
    input_method: InputMethod = InputMethod.FORM


class HealthCheckVoiceRequest(BaseModel):
    raw_transcript: str = Field(min_length=1)
    has_estimated_value: bool = True

    @field_validator("raw_transcript")
    @classmethod
    def validate_raw_transcript(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("raw_transcript must not be blank.")
        return stripped


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

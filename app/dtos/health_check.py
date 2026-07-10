from datetime import datetime

from pydantic import BaseModel, Field

from app.dtos.base import BaseSerializerModel
from app.models.enums import HealthCheckStatus, InputMethod


class HealthCheckSessionCreateRequest(BaseModel):
    input_method: InputMethod = InputMethod.FORM


class HealthCheckVoiceRequest(BaseModel):
    raw_transcript: str = Field(min_length=1)
    has_estimated_value: bool = True


class HealthCheckSessionResponse(BaseSerializerModel):
    session_id: int
    status: HealthCheckStatus
    input_method: InputMethod
    raw_transcript: str | None
    has_estimated_value: bool
    created_at: datetime
    completed_at: datetime | None

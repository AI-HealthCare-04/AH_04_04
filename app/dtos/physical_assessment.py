from datetime import datetime
from decimal import Decimal

from pydantic import BaseModel, Field, model_validator

from app.dtos.base import BaseSerializerModel
from app.models.enums import AssessmentType


class PhysicalAssessmentCreateRequest(BaseModel):
    session_id: int | None = None
    assessment_type: AssessmentType = AssessmentType.INITIAL
    chair_stand_5_time_sec: Decimal | None = Field(default=None, gt=0)
    chair_stand_skipped: bool = False
    walk_6m_time_sec: Decimal | None = Field(default=None, gt=0)
    walk_6m_distance_m: Decimal | None = Field(default=None, gt=0)
    walk_6m_skipped: bool = False
    pain_reported: bool = False
    dizziness_reported: bool = False
    used_for_level_setting: bool = False

    @model_validator(mode="after")
    def validate_measurements(self) -> "PhysicalAssessmentCreateRequest":
        if not self.chair_stand_skipped and self.chair_stand_5_time_sec is None:
            raise ValueError("chair_stand_5_time_sec is required unless chair_stand_skipped is true.")
        if self.chair_stand_skipped and self.chair_stand_5_time_sec is not None:
            raise ValueError("chair_stand_5_time_sec must be omitted when chair_stand_skipped is true.")
        if not self.walk_6m_skipped and self.walk_6m_time_sec is None:
            raise ValueError("walk_6m_time_sec is required unless walk_6m_skipped is true.")
        if self.walk_6m_skipped and (self.walk_6m_time_sec is not None or self.walk_6m_distance_m is not None):
            raise ValueError("walk_6m fields must be omitted when walk_6m_skipped is true.")
        return self


class PhysicalAssessmentResponse(BaseSerializerModel):
    physical_assessment_id: int
    assessment_type: AssessmentType
    chair_stand_5_time_sec: Decimal | None
    chair_stand_skipped: bool
    walk_6m_time_sec: Decimal | None
    walk_6m_distance_m: Decimal | None
    walk_6m_speed_mps: Decimal | None
    walk_6m_skipped: bool
    pain_reported: bool
    dizziness_reported: bool
    used_for_level_setting: bool
    created_at: datetime

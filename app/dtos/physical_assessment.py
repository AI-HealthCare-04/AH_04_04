from decimal import Decimal

from pydantic import BaseModel, Field, model_validator

from app.models.enums import ActivityLevel, AssessmentType, LevelReason


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

    @model_validator(mode="after")
    def validate_measurements(self) -> "PhysicalAssessmentCreateRequest":
        # 밴드 산출 입력인 5STS(chair_stand)는 필수(스킵 아니면).
        if not self.chair_stand_skipped and self.chair_stand_5_time_sec is None:
            raise ValueError("chair_stand_5_time_sec is required unless chair_stand_skipped is true.")
        if self.chair_stand_skipped and self.chair_stand_5_time_sec is not None:
            raise ValueError("chair_stand_5_time_sec must be omitted when chair_stand_skipped is true.")
        # 6m 걷기는 밴드에 쓰지 않는 확장/기록용 → 선택 입력(필수 아님). skipped=True면 값 생략만 강제.
        if self.walk_6m_skipped and (self.walk_6m_time_sec is not None or self.walk_6m_distance_m is not None):
            raise ValueError("walk_6m fields must be omitted when walk_6m_skipped is true.")
        return self


class PhysicalAssessmentActivityProfile(BaseModel):
    current_level: ActivityLevel
    level_reason: LevelReason


class PhysicalAssessmentResponse(BaseModel):
    physical_assessment_id: int
    walk_6m_speed_mps: Decimal | None
    used_for_level_setting: bool
    activity_profile: PhysicalAssessmentActivityProfile

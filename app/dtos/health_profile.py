from datetime import date, datetime
from decimal import Decimal

from pydantic import BaseModel, Field

from app.models.enums import ActivityInputSource, InputMethod, KidneyStatus, ProteinRestrictionStatus, Sex


class HealthProfileCreateRequest(BaseModel):
    session_id: int | None = None
    birth_date: date
    sex: Sex
    height_cm: Decimal = Field(gt=0)
    weight_kg: Decimal = Field(gt=0)
    waist_cm: Decimal | None = Field(default=None, gt=0)
    walking_practice: bool
    strength_exercise: bool
    kidney_status: KidneyStatus = KidneyStatus.UNKNOWN
    protein_restriction_status: ProteinRestrictionStatus = ProteinRestrictionStatus.UNKNOWN


class HealthProfileResponse(BaseModel):
    profile_id: int
    birth_date: date
    age: int
    sex: Sex
    height_cm: Decimal
    weight_kg: Decimal
    bmi: Decimal
    waist_cm: Decimal | None
    walking_practice: bool
    strength_exercise: bool
    activity_input_source: ActivityInputSource
    activity_window_days: int | None
    kidney_status: KidneyStatus
    protein_restriction_status: ProteinRestrictionStatus
    protein_challenge_allowed: bool
    input_method: InputMethod
    has_estimated_value: bool
    created_at: datetime

from datetime import date
from decimal import Decimal

from pydantic import BaseModel, Field, field_serializer

from app.dtos.base import KstDatetime
from app.models.enums import ActivityInputSource, InputMethod, KidneyStatus, ProteinRestrictionStatus, Sex


class HealthProfileCreateRequest(BaseModel):
    session_id: int | None = None
    birth_date: date
    sex: Sex
    height_cm: Decimal = Field(gt=0)
    weight_kg: Decimal = Field(gt=0)
    waist_cm: Decimal | None = Field(default=None, gt=0)
    walk_days: int = Field(ge=0, le=7)
    musc_days: int = Field(ge=0, le=5)
    kidney_status: KidneyStatus = KidneyStatus.UNKNOWN
    protein_restriction_status: ProteinRestrictionStatus = ProteinRestrictionStatus.UNKNOWN
    activity_input_source: ActivityInputSource
    input_method: InputMethod
    has_estimated_value: bool


class HealthProfileCreateResponse(BaseModel):
    profile_id: int
    bmi: Decimal
    protein_challenge_allowed: bool

    @field_serializer("bmi")
    def serialize_bmi_as_number(self, value: Decimal) -> float:
        return float(value)


class HealthProfilePatchRequest(BaseModel):
    """설정 '내 정보' 편집(#기록탭 §2). 보낸 필드만 최신 프로필에 덮어 **새 스냅샷 행**을 만든다.

    성별·생년월일·활동일수는 편집 대상이 아니라(표시만) 여기 없다.
    허리둘레는 명시적 null 로 '측정 안 함'을 지울 수 있다(model_fields_set 로 미전송과 구분).
    """

    height_cm: Decimal | None = Field(default=None, gt=0)
    weight_kg: Decimal | None = Field(default=None, gt=0)
    waist_cm: Decimal | None = Field(default=None, gt=0)
    kidney_status: KidneyStatus | None = None
    # 단백질(고단백 식사) 미션 게이트(#304): 신장상태와 함께 protein_challenge_allowed 를 정한다. 내정보에서
    #   이 값을 못 바꾸면 온보딩에서 제한으로 저장된 뒤 미션을 영영 되돌릴 수 없다 → 편집 가능하게 추가(미전송이면 유지).
    protein_restriction_status: ProteinRestrictionStatus | None = None


class HealthProfileResponse(BaseModel):
    profile_id: int
    birth_date: date
    age: int
    sex: Sex
    height_cm: Decimal
    weight_kg: Decimal
    bmi: Decimal
    waist_cm: Decimal | None
    walk_days: int
    musc_days: int
    activity_input_source: ActivityInputSource
    activity_window_days: int | None
    kidney_status: KidneyStatus
    protein_restriction_status: ProteinRestrictionStatus
    protein_challenge_allowed: bool
    input_method: InputMethod
    has_estimated_value: bool
    created_at: KstDatetime

    @field_serializer("height_cm", "weight_kg", "bmi", "waist_cm")
    def serialize_decimal_as_number(self, value: Decimal | None) -> float | None:
        if value is None:
            return None
        return float(value)

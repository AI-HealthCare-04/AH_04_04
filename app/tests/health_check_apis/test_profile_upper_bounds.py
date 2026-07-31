"""건강 프로필·체력검사 DTO 상한(le=) 검증 — #298 A-2 근본 방어.

극단값(예: 99999)이 DB Numeric(5,2)=999.99 를 넘어 DataError 500 이 되기 전에,
DTO 단계에서 422(ValidationError)로 거르는지 확인한다. 정상 입력은 통과해야 한다.
"""

from datetime import date
from decimal import Decimal

import pytest
from pydantic import ValidationError

from app.dtos.health_profile import HealthProfileCreateRequest
from app.dtos.physical_assessment import PhysicalAssessmentCreateRequest
from app.models.enums import ActivityInputSource, InputMethod, Sex


def _profile(**override):
    base = dict(
        birth_date=date(1958, 3, 1),
        sex=Sex.MALE,
        height_cm=Decimal("170"),
        weight_kg=Decimal("65"),
        walk_days=5,
        musc_days=2,
        activity_input_source=ActivityInputSource.SELF_REPORT,
        input_method=InputMethod.FORM,
        has_estimated_value=False,
    )
    base.update(override)
    return base


def test_normal_profile_ok():
    HealthProfileCreateRequest(**_profile())  # 정상 입력은 통과


def test_height_over_limit_rejected():
    with pytest.raises(ValidationError):
        HealthProfileCreateRequest(**_profile(height_cm=Decimal("99999")))


def test_weight_over_limit_rejected():
    with pytest.raises(ValidationError):
        HealthProfileCreateRequest(**_profile(weight_kg=Decimal("999")))


def test_waist_over_limit_rejected():
    with pytest.raises(ValidationError):
        HealthProfileCreateRequest(**_profile(waist_cm=Decimal("500")))


def test_height_at_upper_bound_ok():
    HealthProfileCreateRequest(**_profile(height_cm=Decimal("250")))  # le=250 경계 허용


def test_chair_stand_over_limit_rejected():
    with pytest.raises(ValidationError):
        PhysicalAssessmentCreateRequest(chair_stand_5_time_sec=Decimal("999999"))


def test_chair_stand_normal_ok():
    PhysicalAssessmentCreateRequest(chair_stand_5_time_sec=Decimal("12.4"))  # 정상 측정값

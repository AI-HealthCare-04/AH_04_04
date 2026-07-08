from decimal import Decimal

import pytest
from pydantic import ValidationError

from app.dtos.physical_assessment import PhysicalAssessmentCreateRequest
from app.services.physical_assessment import PhysicalAssessmentService


def test_physical_assessment_requires_chair_stand_time_when_not_skipped() -> None:
    with pytest.raises(ValidationError):
        PhysicalAssessmentCreateRequest(walk_6m_time_sec=Decimal("7.2"))


def test_physical_assessment_requires_walk_time_when_not_skipped() -> None:
    with pytest.raises(ValidationError):
        PhysicalAssessmentCreateRequest(chair_stand_5_time_sec=Decimal("12.3"))


def test_physical_assessment_allows_skipped_measurements() -> None:
    data = PhysicalAssessmentCreateRequest(chair_stand_skipped=True, walk_6m_skipped=True)

    assert data.chair_stand_5_time_sec is None
    assert data.walk_6m_time_sec is None


def test_walk_speed_uses_two_decimal_places() -> None:
    speed = PhysicalAssessmentService._calculate_walk_speed(Decimal("6.00"), Decimal("7.00"))

    assert speed == Decimal("0.86")

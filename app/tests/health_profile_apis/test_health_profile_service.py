from datetime import date, datetime
from decimal import Decimal

import pytest
from pydantic import ValidationError

from app.dtos.health_profile import HealthProfileCreateRequest, HealthProfileCreateResponse, HealthProfileResponse
from app.models.enums import (
    ActivityInputSource,
    InputMethod,
    KidneyStatus,
    ProteinRestrictionStatus,
    Sex,
)
from app.services.health_profile import HealthProfileService


def test_bmi_is_calculated_with_one_decimal_place() -> None:
    bmi = HealthProfileService.calculate_bmi(Decimal("154.0"), Decimal("50.0"))

    assert bmi == Decimal("21.1")


def test_age_is_calculated_without_storing_age() -> None:
    age = HealthProfileService.calculate_age(date(1952, 7, 8), today=date(2026, 7, 9))

    assert age == 74


def test_protein_challenge_is_allowed_only_when_both_statuses_are_none() -> None:
    allowed = HealthProfileService.is_protein_challenge_allowed(
        KidneyStatus.NONE,
        ProteinRestrictionStatus.NONE,
    )

    assert allowed is True


@pytest.mark.parametrize(
    ("kidney_status", "protein_restriction_status"),
    [
        (KidneyStatus.UNKNOWN, ProteinRestrictionStatus.UNKNOWN),
        (KidneyStatus.NONE, ProteinRestrictionStatus.UNKNOWN),
        (KidneyStatus.UNKNOWN, ProteinRestrictionStatus.NONE),
        (KidneyStatus.KIDNEY_DISEASE, ProteinRestrictionStatus.NONE),
        (KidneyStatus.DIALYSIS, ProteinRestrictionStatus.UNKNOWN),
        (KidneyStatus.NONE, ProteinRestrictionStatus.RESTRICTED),
    ],
)
def test_protein_challenge_is_blocked_for_kidney_or_restriction_status(
    kidney_status: KidneyStatus,
    protein_restriction_status: ProteinRestrictionStatus,
) -> None:
    allowed = HealthProfileService.is_protein_challenge_allowed(
        kidney_status,
        protein_restriction_status,
    )

    assert allowed is False


def test_health_profile_request_accepts_unknown_waist() -> None:
    data = HealthProfileCreateRequest(
        birth_date=date(1952, 7, 8),
        sex=Sex.FEMALE,
        height_cm=Decimal("154.0"),
        weight_kg=Decimal("50.0"),
        waist_cm=None,
        walking_practice=True,
        strength_exercise=False,
        activity_input_source=ActivityInputSource.SELF_REPORT,
        input_method=InputMethod.FORM,
        has_estimated_value=False,
    )

    assert data.waist_cm is None
    assert data.kidney_status == KidneyStatus.UNKNOWN
    assert data.protein_restriction_status == ProteinRestrictionStatus.UNKNOWN
    assert data.activity_input_source == ActivityInputSource.SELF_REPORT
    assert data.input_method == InputMethod.FORM
    assert data.has_estimated_value is False


def test_health_profile_response_serializes_decimals_as_numbers() -> None:
    response = HealthProfileResponse(
        profile_id=1,
        birth_date=date(1952, 7, 8),
        age=74,
        sex=Sex.FEMALE,
        height_cm=Decimal("154.00"),
        weight_kg=Decimal("50.00"),
        bmi=Decimal("21.1"),
        waist_cm=None,
        walking_practice=True,
        strength_exercise=False,
        activity_input_source=ActivityInputSource.SELF_REPORT,
        activity_window_days=None,
        kidney_status=KidneyStatus.NONE,
        protein_restriction_status=ProteinRestrictionStatus.NONE,
        protein_challenge_allowed=True,
        input_method=InputMethod.FORM,
        has_estimated_value=False,
        created_at=datetime(2026, 7, 9, 12, 0, 0),
    )

    dumped = response.model_dump(mode="json")

    assert dumped["height_cm"] == 154.0
    assert dumped["weight_kg"] == 50.0
    assert dumped["bmi"] == 21.1
    assert dumped["waist_cm"] is None


def test_health_profile_create_response_matches_post_contract() -> None:
    response = HealthProfileCreateResponse(
        profile_id=55,
        bmi=Decimal("22.7"),
        protein_challenge_allowed=True,
    )

    dumped = response.model_dump(mode="json")

    assert dumped == {
        "profile_id": 55,
        "bmi": 22.7,
        "protein_challenge_allowed": True,
    }


def test_health_profile_request_rejects_invalid_enum_value() -> None:
    with pytest.raises(ValidationError):
        HealthProfileCreateRequest.model_validate(
            {
                "birth_date": date(1952, 7, 8),
                "sex": "other",
                "height_cm": Decimal("154.0"),
                "weight_kg": Decimal("50.0"),
                "walking_practice": True,
                "strength_exercise": False,
                "activity_input_source": "self_report",
                "input_method": "form",
                "has_estimated_value": False,
            }
        )

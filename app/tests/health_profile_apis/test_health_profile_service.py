from datetime import date
from decimal import Decimal

import pytest
from pydantic import ValidationError

from app.dtos.health_profile import HealthProfileCreateRequest
from app.models.enums import KidneyStatus, ProteinRestrictionStatus, Sex
from app.services.health_profile import HealthProfileService


def test_bmi_is_calculated_with_one_decimal_place() -> None:
    bmi = HealthProfileService.calculate_bmi(Decimal("154.0"), Decimal("50.0"))

    assert bmi == Decimal("21.1")


def test_age_is_calculated_without_storing_age() -> None:
    age = HealthProfileService.calculate_age(date(1952, 7, 8), today=date(2026, 7, 9))

    assert age == 74


def test_protein_challenge_is_allowed_when_status_is_unknown() -> None:
    allowed = HealthProfileService.is_protein_challenge_allowed(
        KidneyStatus.UNKNOWN,
        ProteinRestrictionStatus.UNKNOWN,
    )

    assert allowed is True


@pytest.mark.parametrize(
    ("kidney_status", "protein_restriction_status"),
    [
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
    )

    assert data.waist_cm is None
    assert data.kidney_status == KidneyStatus.UNKNOWN
    assert data.protein_restriction_status == ProteinRestrictionStatus.UNKNOWN


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
            }
        )

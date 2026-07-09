from types import SimpleNamespace

from app.dtos.risk_prediction import CareStage, RiskPredictionCreateResponse
from app.models.enums import ModelVariant, OnboardingStatus, RiskLevel
from app.services.risk_prediction import RiskPredictionService


def test_care_stage_uses_api_contract_values() -> None:
    assert RiskPredictionService._care_stage_from_risk_level(RiskLevel.LOW) == CareStage.GOOD
    assert RiskPredictionService._care_stage_from_risk_level(RiskLevel.MEDIUM) == CareStage.MAINTAIN
    assert RiskPredictionService._care_stage_from_risk_level(RiskLevel.HIGH) == CareStage.ACTION_NEEDED


def test_display_message_does_not_expose_internal_score() -> None:
    message = RiskPredictionService._display_message(CareStage.ACTION_NEEDED)

    assert "score" not in message.lower()
    assert "probability" not in message.lower()
    assert "점수" not in message
    assert "확률" not in message


def test_risk_prediction_response_includes_public_model_context() -> None:
    prediction = SimpleNamespace(
        prediction_id=11,
        profile_id=22,
        model_variant=ModelVariant.WITH_WAIST,
        internal_risk_level=RiskLevel.HIGH,
    )

    response = RiskPredictionService(session=None)._to_response(prediction)  # type: ignore[arg-type]

    assert response.prediction_id == 11
    assert response.profile_id == 22
    assert response.model_variant == "with_waist"
    assert response.care_stage == CareStage.ACTION_NEEDED


def test_create_response_includes_onboarding_status() -> None:
    response = RiskPredictionCreateResponse(
        prediction_id=1,
        profile_id=2,
        model_variant=ModelVariant.MINIMAL.value,
        care_stage=CareStage.GOOD,
        display_message="ok",
        onboarding_status=OnboardingStatus.COMPLETED.value,
    )

    assert response.onboarding_status == "completed"

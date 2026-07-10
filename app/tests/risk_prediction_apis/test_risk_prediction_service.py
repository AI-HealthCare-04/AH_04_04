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


def test_display_messages_use_readable_korean_copy() -> None:
    assert (
        RiskPredictionService._display_message(CareStage.GOOD)
        == "현재 입력값 기준으로 위험도는 낮은 편이에요. 지금처럼 생활습관 미션을 이어가 보세요."
    )
    assert (
        RiskPredictionService._display_message(CareStage.MAINTAIN)
        == "생활습관 관리가 필요한 상태예요. 걷기와 근력 운동을 꾸준히 이어가 보세요."
    )
    assert (
        RiskPredictionService._display_message(CareStage.ACTION_NEEDED)
        == "근감소 위험 신호가 있어요. 무리하지 않는 범위에서 맞춤 운동 미션을 시작해 보세요."
    )


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
    assert response.disclaimer == "본 결과는 참고용이며 의학적 진단이 아닙니다."


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

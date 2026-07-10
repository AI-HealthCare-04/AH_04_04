from datetime import datetime
from decimal import Decimal
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


def test_history_item_includes_dashboard_trend_fields() -> None:
    prediction = SimpleNamespace(
        prediction_id=11,
        created_at=datetime(2026, 7, 10, 12, 0, 0),
        internal_risk_level=RiskLevel.MEDIUM,
        internal_risk_score=Decimal("0.427"),
        model_variant=ModelVariant.WITH_WAIST,
    )

    item = RiskPredictionService._to_history_item(prediction)  # type: ignore[arg-type]

    assert item.prediction_id == 11
    assert item.created_at == datetime(2026, 7, 10, 12, 0, 0)
    assert item.care_stage == CareStage.MAINTAIN
    assert item.risk_level == RiskLevel.MEDIUM
    assert item.risk_score == Decimal("0.427")
    assert item.model_variant == ModelVariant.WITH_WAIST


async def test_get_recent_predictions_returns_history_items_in_repo_order() -> None:
    predictions: list[object] = [
        SimpleNamespace(
            prediction_id=12,
            created_at=datetime(2026, 7, 10, 12, 0, 0),
            internal_risk_level=RiskLevel.HIGH,
            internal_risk_score=Decimal("0.751"),
            model_variant=ModelVariant.MINIMAL,
        ),
        SimpleNamespace(
            prediction_id=11,
            created_at=datetime(2026, 7, 9, 12, 0, 0),
            internal_risk_level=RiskLevel.LOW,
            internal_risk_score=Decimal("0.121"),
            model_variant=ModelVariant.WITH_WAIST,
        ),
    ]
    repo = SimpleNamespace(called_with=None)

    async def fake_get_recent_predictions(user_id: int, limit: int) -> list[object]:
        repo.called_with = (user_id, limit)
        return predictions

    repo.get_recent_predictions = fake_get_recent_predictions
    service = RiskPredictionService(session=None)  # type: ignore[arg-type]
    service.prediction_repo = repo  # type: ignore[assignment]
    user = SimpleNamespace(user_id=1)

    response = await service.get_recent_predictions(user, limit=2)  # type: ignore[arg-type]

    assert repo.called_with == (1, 2)
    assert [item.prediction_id for item in response.predictions] == [12, 11]
    assert response.predictions[0].care_stage == CareStage.ACTION_NEEDED
    assert response.predictions[1].care_stage == CareStage.GOOD
    assert response.predictions[0].risk_level == RiskLevel.HIGH
    assert response.predictions[1].risk_score == Decimal("0.121")


async def test_get_recent_predictions_returns_empty_list_for_non_positive_limit() -> None:
    repo = SimpleNamespace(called=False)

    async def fake_get_recent_predictions(user_id: int, limit: int) -> list[object]:
        repo.called = True
        return []

    repo.get_recent_predictions = fake_get_recent_predictions
    service = RiskPredictionService(session=None)  # type: ignore[arg-type]
    service.prediction_repo = repo  # type: ignore[assignment]

    response = await service.get_recent_predictions(SimpleNamespace(user_id=1), limit=0)  # type: ignore[arg-type]

    assert response.predictions == []
    assert repo.called is False

from datetime import date, datetime
from decimal import Decimal
from types import SimpleNamespace
from typing import cast

import pytest
from pydantic import ValidationError

from app.dtos.risk_prediction import CareStage, RiskPredictionCreateResponse, RiskPredictionReassessRequest
from app.models.enums import (
    ActivityInputSource,
    ActivityType,
    InputMethod,
    KidneyStatus,
    ModelVariant,
    OnboardingStatus,
    ProteinRestrictionStatus,
    RiskLevel,
    Sex,
)
from app.models.health import HealthProfile
from app.models.predictions import RiskPrediction
from app.models.users import User
from app.services.risk_prediction import RiskPredictionService


def _reassessment_activity_logs() -> list[object]:
    logs: list[object] = []
    logs.extend(
        [
            SimpleNamespace(
                activity_date=date(2026, 7, day),
                activity_type=ActivityType.WALKING,
                duration_min=Decimal("30"),
                reps=None,
                sets=None,
            )
            for day in range(1, 11)
        ]
    )
    logs.extend(
        [
            SimpleNamespace(
                activity_date=date(2026, 7, day),
                activity_type=ActivityType.SEATED_EXERCISE,
                duration_min=Decimal("10"),
                reps=None,
                sets=None,
            )
            for day in (1, 3, 8, 10)
        ]
    )
    return logs


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
        == "지금 컨디션이 좋아요. 지금처럼 생활습관 미션을 이어가면 근력을 잘 지킬 수 있어요."
    )
    assert (
        RiskPredictionService._display_message(CareStage.MAINTAIN)
        == "조금만 더 챙기면 좋은 단계예요. 걷기와 근력 운동을 꾸준히 이어가 봐요."
    )
    assert (
        RiskPredictionService._display_message(CareStage.ACTION_NEEDED)
        == "근력과 활동량을 더 챙기면 좋은 시점이에요. 무리하지 않는 범위에서 맞춤 운동을 천천히 시작해 봐요."
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


def test_reassess_request_accepts_only_supported_activity_windows() -> None:
    assert RiskPredictionReassessRequest(activity_window_days=7).activity_window_days == 7
    assert RiskPredictionReassessRequest(activity_window_days=14).activity_window_days == 14

    with pytest.raises(ValidationError):
        RiskPredictionReassessRequest.model_validate({"activity_window_days": 30})


def test_reassess_response_uses_v73_contract_without_model_variant() -> None:
    prediction = SimpleNamespace(
        prediction_id=90,
        profile_id=72,
        internal_risk_level=RiskLevel.MEDIUM,
    )

    response = RiskPredictionService(session=None)._to_reassess_response(prediction)  # type: ignore[arg-type]
    dumped = response.model_dump(mode="json")

    assert dumped == {
        "profile_id": 72,
        "prediction_id": 90,
        "care_stage": "maintain",
        "display_message": RiskPredictionService._display_message(CareStage.MAINTAIN),
        "disclaimer": "본 결과는 참고용이며 의학적 진단이 아닙니다.",
        "activity_input_source": ActivityInputSource.SERVICE_LOG.value,
    }
    assert "model_variant" not in dumped


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
    assert item.model_variant == ModelVariant.WITH_WAIST
    # 비노출(#57): 내부 risk_level/risk_score 는 이력 항목에 없다.
    assert not hasattr(item, "risk_level")
    assert not hasattr(item, "risk_score")


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
    # 순서·내용은 care_stage(표시용)로 검증. 내부 risk_level/risk_score 는 비노출이라 확인 대상 아님.
    assert response.predictions[0].care_stage == CareStage.ACTION_NEEDED
    assert response.predictions[1].care_stage == CareStage.GOOD


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


async def test_reassess_uses_latest_user_entered_profile_as_source() -> None:  # noqa: C901
    source_profile = HealthProfile(
        profile_id=55,
        user_id=1,
        session_id=10,
        birth_date=date(1958, 3, 1),
        sex=Sex.MALE,
        height_cm=Decimal("160.00"),
        weight_kg=Decimal("58.00"),
        bmi=Decimal("22.7"),
        waist_cm=Decimal("82.00"),
        walking_practice=True,
        strength_exercise=False,
        activity_input_source=ActivityInputSource.SELF_REPORT,
        activity_window_days=None,
        kidney_status=KidneyStatus.NONE,
        protein_restriction_status=ProteinRestrictionStatus.NONE,
        protein_challenge_allowed=True,
        input_method=InputMethod.FORM,
        has_estimated_value=False,
    )

    class _ProfileRepo:
        def __init__(self) -> None:
            self.created_profile: HealthProfile | None = None
            self.latest_called_with: int | None = None

        async def get_latest_profile(self, user_id: int) -> HealthProfile:
            self.latest_called_with = user_id
            return source_profile

        async def create_profile(self, profile: HealthProfile) -> HealthProfile:
            profile.profile_id = 72
            self.created_profile = profile
            return profile

    class _PredictionRepo:
        def __init__(self) -> None:
            self.created_prediction: RiskPrediction | None = None

        async def create_risk_prediction(self, prediction: RiskPrediction) -> RiskPrediction:
            prediction.prediction_id = 90
            self.created_prediction = prediction
            return prediction

    class _DashboardRepo:
        def __init__(self) -> None:
            self.called_with: tuple[int, date, date] | None = None

        async def get_activity_logs_between(self, user_id: int, start: date, end: date) -> list[object]:
            self.called_with = (user_id, start, end)
            return _reassessment_activity_logs()

    class _Predictor:
        async def predict(self, features: object) -> object:
            return SimpleNamespace(
                model_version="test",
                model_variant=ModelVariant.WITH_WAIST,
                risk_score=0.42,
                risk_level=RiskLevel.MEDIUM,
                input_snapshot={},
            )

    session = SimpleNamespace(committed=False, refreshed=None)

    async def commit() -> None:
        session.committed = True

    async def refresh(instance: object) -> None:
        session.refreshed = instance

    session.commit = commit
    session.refresh = refresh

    service = RiskPredictionService(session=None, predictor=_Predictor())  # type: ignore[arg-type]
    profile_repo = _ProfileRepo()
    prediction_repo = _PredictionRepo()
    dashboard_repo = _DashboardRepo()
    service.session = session  # type: ignore[assignment]
    service.profile_repo = profile_repo  # type: ignore[assignment]
    service.prediction_repo = prediction_repo  # type: ignore[assignment]
    service.dashboard_repo = dashboard_repo  # type: ignore[assignment]

    response = await service.reassess_latest_profile(
        cast(User, SimpleNamespace(user_id=1)),
        RiskPredictionReassessRequest(activity_window_days=14),
    )

    assert profile_repo.latest_called_with == 1
    assert profile_repo.created_profile is not None
    assert profile_repo.created_profile.profile_id == 72
    assert profile_repo.created_profile.activity_input_source == ActivityInputSource.SERVICE_LOG
    assert profile_repo.created_profile.activity_window_days == 14
    assert profile_repo.created_profile.input_method == InputMethod.SERVICE_LOG
    assert profile_repo.created_profile.walking_practice is True
    assert profile_repo.created_profile.strength_exercise is True
    assert profile_repo.created_profile.has_estimated_value is True
    assert dashboard_repo.called_with is not None
    assert dashboard_repo.called_with[0] == 1
    assert response.profile_id == 72
    assert response.prediction_id == 90
    assert response.activity_input_source == ActivityInputSource.SERVICE_LOG
    assert session.committed is True

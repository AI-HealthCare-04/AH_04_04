from datetime import date, datetime, timedelta
from decimal import Decimal
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.core.utils.clock import today_kst
from app.dtos.risk_prediction import (
    BaselineChangeReason,
    CareStage,
    RiskComparisonStatus,
    RiskPredictionCreateResponse,
    RiskPredictionReassessRequest,
)
from app.ml.predictor import AgeNotSupportedError, FeatureContribution
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
from app.services.risk_prediction import (
    COHORT_SEX_CODES,
    RiskPredictionService,
    _snapshot_sex,
    next_reassess_available_at,
)


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
        internal_risk_score=Decimal("0.427"),
    )

    response = RiskPredictionService(session=None)._to_response(prediction)  # type: ignore[arg-type]

    assert response.prediction_id == 11
    assert response.profile_id == 22
    assert response.model_variant == "with_waist"
    assert response.risk_score == 0.427
    assert response.muscle_score is None
    assert response.score_band is None
    assert response.cohort_version is None
    assert response.care_stage == CareStage.ACTION_NEEDED
    assert response.disclaimer == "본 결과는 참고용이며 의학적 진단이 아닙니다."
    # 조회 경로(/me/latest 등)는 contributions 를 전달하지 않으므로 빈 목록이다(#406) —
    #   기여도는 저장하지 않고 create·재계산 응답에만 싣는다. 앱은 로컬 캐시를 쓴다.
    assert response.contributions == []


def test_create_response_includes_onboarding_status() -> None:
    response = RiskPredictionCreateResponse(
        prediction_id=1,
        profile_id=2,
        model_variant=ModelVariant.MINIMAL.value,
        risk_score=0.121,
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
        internal_risk_score=Decimal("0.427"),
    )

    response = RiskPredictionService(session=None)._to_reassess_response(prediction)  # type: ignore[arg-type]
    dumped = response.model_dump(mode="json")

    assert dumped == {
        "profile_id": 72,
        "prediction_id": 90,
        "risk_score": 0.427,
        "muscle_score": None,
        "score_band": None,
        "cohort_version": None,
        "care_stage": "maintain",
        "display_message": RiskPredictionService._display_message(CareStage.MAINTAIN),
        "disclaimer": "본 결과는 참고용이며 의학적 진단이 아닙니다.",
        "activity_input_source": ActivityInputSource.SERVICE_LOG.value,
        # contributions 를 전달하지 않고 만든 응답(조회 경로)은 빈 목록(#406) — 계약을 깨지 않는다.
        "contributions": [],
        # 하루 1회 정책(#388): 기본은 '이번 호출로 새로 계산' + 다음 가능 시각(다음 KST 자정).
        "recalculated": True,
        "next_available_at": dumped["next_available_at"],
    }
    assert "model_variant" not in dumped
    assert dumped["next_available_at"] is not None


def test_history_item_exposes_continuous_score_without_internal_model_fields() -> None:
    prediction = SimpleNamespace(
        prediction_id=11,
        created_at=datetime(2026, 7, 10, 12, 0, 0),
        internal_risk_level=RiskLevel.MEDIUM,
        internal_risk_score=Decimal("0.427"),
        model_version="awgs2025-v2",
        model_variant=ModelVariant.WITH_WAIST,
    )

    item = RiskPredictionService._to_history_item(prediction)  # type: ignore[arg-type]

    assert item.prediction_id == 11
    assert item.created_at == datetime(2026, 7, 10, 12, 0, 0)
    assert item.risk_score == 0.427
    assert item.muscle_score is None
    assert item.score_band is None
    assert item.cohort_version is None
    assert item.change_percentage_points is None
    assert item.comparison_status == RiskComparisonStatus.BASELINE
    assert item.care_stage == CareStage.MAINTAIN
    # 내부 등급과 모델 식별자는 계속 비노출한다.
    assert not hasattr(item, "risk_level")
    assert not hasattr(item, "model_version")
    assert not hasattr(item, "model_variant")


async def test_get_recent_predictions_returns_chronological_continuous_trend() -> None:
    predictions: list[object] = [
        SimpleNamespace(
            prediction_id=13,
            created_at=datetime(2026, 7, 11, 12, 0, 0),
            profile_id=55,
            internal_risk_level=RiskLevel.MEDIUM,
            internal_risk_score=Decimal("0.281"),
            model_version="awgs2025-v2",
            model_variant=ModelVariant.MINIMAL,
        ),
        SimpleNamespace(
            prediction_id=12,
            created_at=datetime(2026, 7, 10, 12, 0, 0),
            profile_id=55,
            internal_risk_level=RiskLevel.MEDIUM,
            internal_risk_score=Decimal("0.324"),
            model_version="awgs2025-v2",
            model_variant=ModelVariant.MINIMAL,
        ),
        SimpleNamespace(
            prediction_id=11,
            created_at=datetime(2026, 7, 9, 12, 0, 0),
            profile_id=55,
            internal_risk_level=RiskLevel.LOW,
            internal_risk_score=Decimal("0.400"),
            model_version="awgs2025-days-v2",
            model_variant=ModelVariant.WITH_WAIST,
        ),
    ]
    repo = SimpleNamespace(called_with=None)

    async def fake_get_recent_predictions(user_id: int, limit: int) -> list[object]:
        repo.called_with = (user_id, limit)
        return predictions

    repo.get_recent_predictions = fake_get_recent_predictions

    # 신체 정보 변경 판정(#389 C)이 프로필 값을 보므로 조회를 stub 한다. 여기서는 프로필이 없어
    #   profile_changed 가 전부 False 여야 한다 — 이 테스트의 관심사는 추이 순서·증감이다.
    profile_repo = SimpleNamespace(requested_ids=None)

    async def fake_get_profiles_by_ids(profile_ids: object, user_id: int) -> dict[int, object]:
        profile_repo.requested_ids = sorted(profile_ids)  # type: ignore[call-overload]
        return {}

    profile_repo.get_profiles_by_ids = fake_get_profiles_by_ids
    service = RiskPredictionService(session=None)  # type: ignore[arg-type]
    service.prediction_repo = repo  # type: ignore[assignment]
    service.profile_repo = profile_repo  # type: ignore[assignment]
    user = SimpleNamespace(user_id=1)

    response = await service.get_recent_predictions(user, limit=3)  # type: ignore[arg-type]

    assert repo.called_with == (1, 3)
    assert [item.prediction_id for item in response.predictions] == [11, 12, 13]
    assert [item.risk_score for item in response.predictions] == [0.4, 0.324, 0.281]
    assert [item.muscle_score for item in response.predictions] == [None, None, None]
    assert [item.change_percentage_points for item in response.predictions] == [None, None, -4.3]
    assert [item.comparison_status for item in response.predictions] == [
        RiskComparisonStatus.BASELINE,
        RiskComparisonStatus.MODEL_CHANGED,
        RiskComparisonStatus.COMPARABLE,
    ]


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


async def test_predict_and_save_returns_422_for_under_65_model_gate() -> None:
    profile = HealthProfile(
        profile_id=55,
        user_id=1,
        session_id=10,
        birth_date=date(1962, 3, 1),
        sex=Sex.MALE,
        height_cm=Decimal("160.00"),
        weight_kg=Decimal("58.00"),
        bmi=Decimal("22.7"),
        waist_cm=None,
        walk_days=5,
        musc_days=0,
        activity_input_source=ActivityInputSource.SELF_REPORT,
        activity_window_days=None,
        kidney_status=KidneyStatus.NONE,
        protein_restriction_status=ProteinRestrictionStatus.NONE,
        protein_challenge_allowed=True,
        input_method=InputMethod.FORM,
        has_estimated_value=False,
    )

    class _Predictor:
        async def predict(self, features: object) -> object:
            raise AgeNotSupportedError(64)

    service = RiskPredictionService(session=None, predictor=_Predictor())  # type: ignore[arg-type]

    with pytest.raises(HTTPException) as exc_info:
        await service._predict_and_save(cast(User, SimpleNamespace(user_id=1)), profile)

    assert exc_info.value.status_code == 422
    assert isinstance(exc_info.value.detail, dict)
    assert exc_info.value.detail["code"] == "sarcopenia_prediction_preparing"


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
        walk_days=5,
        musc_days=0,
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
        def __init__(self, today_reassessment: RiskPrediction | None = None) -> None:
            self.created_prediction: RiskPrediction | None = None
            self.today_reassessment = today_reassessment
            self.lock_order: list[str] = []

        async def lock_user_for_reassess(self, user_id: int) -> None:
            self.lock_order.append("lock")

        async def create_risk_prediction(self, prediction: RiskPrediction) -> RiskPrediction:
            prediction.prediction_id = 90
            self.created_prediction = prediction
            return prediction

        async def get_today_reassessment(self, user_id: int) -> RiskPrediction | None:
            self.lock_order.append("check")
            return self.today_reassessment

    class _DashboardRepo:
        def __init__(self) -> None:
            self.called_with: tuple[int, date, date] | None = None

        async def get_activity_logs_between(self, user_id: int, start: date, end: date) -> list[object]:
            self.called_with = (user_id, start, end)
            return _reassessment_activity_logs()

    class _Predictor:
        def __init__(self) -> None:
            # 예측기가 **실제로 받은 입력**을 보관한다(리뷰 비차단 제안). 저장 결과만 보면
            #   activity_override 배선이 빠져도 테스트가 통과한다 — 이 PR 의 핵심이 "프로필을
            #   복제하지 않고도 계산 의미를 유지한다"는 것이라 그 연결 고리를 직접 단언한다.
            self.seen_features: dict[str, object] | None = None

        async def predict(self, features: object) -> object:
            self.seen_features = dict(cast(dict[str, object], features))
            return SimpleNamespace(
                model_version="test",
                model_variant=ModelVariant.WITH_WAIST,
                risk_score=0.42,
                risk_level=RiskLevel.MEDIUM,
                muscle_score=81,
                score_band="good",
                score_p_low=0.01,
                score_p_high=0.50,
                score_cohort_age="72",
                score_cohort_version="knhanes2022_2024_v1",
                # 예측기는 계산용으로 정규화 입력을 돌려주지만, 서비스는 여기서 성별만 뽑아 컬럼에
                #   남기고 원본은 저장하지 않는다(#408).
                input_snapshot={"age": 72.0, "sex": 1, "height_cm": 168.0, "weight_kg": 78.0,
                                "bmi": 27.6, "waist_cm": 98.0, "walk_days": 1.0, "musc_days": 0.0},
                # SHAP 기여도(#406)는 예측기가 **점수와 같은 모델 번들로** 계산해 result 에 실어준다
                #   (리뷰 P1 — 서비스는 계산하지 않고 전달만 한다). 노출 3개만.
                score_contributions=(
                    FeatureContribution(feature="musc_days", effect_on_score_log_odds=-0.17),
                    FeatureContribution(feature="walk_days", effect_on_score_log_odds=-0.13),
                    FeatureContribution(feature="waist_cm", effect_on_score_log_odds=-1.29),
                ),
            )

    session = SimpleNamespace(committed=False, refreshed=None)

    async def commit() -> None:
        session.committed = True

    async def refresh(instance: object) -> None:
        session.refreshed = instance

    session.commit = commit
    session.refresh = refresh

    predictor = _Predictor()
    service = RiskPredictionService(session=None, predictor=predictor)  # type: ignore[arg-type]
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
    # 재평가는 프로필 행을 **만들지 않는다**(#408 A+3). 전에는 판별 근거(input_method=SERVICE_LOG)
    #   하나 때문에 생년월일·성별·신체계측까지 매번 복제했다.
    assert profile_repo.created_profile is None
    # 예측은 사용자가 직접 입력한 프로필을 그대로 가리키고, 재평가라는 사실은 예측 행이 들고 있다.
    assert prediction_repo.created_prediction is not None
    assert prediction_repo.created_prediction.profile_id == 55
    assert prediction_repo.created_prediction.is_reassessment is True
    # 활동 일수는 프로필이 아니라 최근 기록에서 세어 **예측 입력으로만** 쓴다.
    assert dashboard_repo.called_with is not None
    assert dashboard_repo.called_with[0] == 1
    # 센 값이 예측기까지 실제로 전달됐는지 직접 단언한다(리뷰 비차단 제안). 로그 조회와 저장 결과만
    #   보면 activity_override 인자가 통째로 빠져도 통과한다 — 그러면 재평가가 가입 시 자가응답으로
    #   계산되면서 겉보기엔 정상이라 알아채기 어렵다.
    #   기대값: 걷기 10일/2주 -> 5, 근력 4일(1·3·8·10)/2주 -> 2.
    #   특히 musc_days 는 원본 프로필 값이 0 이라, 배선이 빠지면 이 단언이 먼저 깨진다.
    assert predictor.seen_features is not None
    assert predictor.seen_features["walk_days"] == 5
    assert predictor.seen_features["musc_days"] == 2
    assert prediction_repo.created_prediction is not None
    assert prediction_repo.created_prediction.muscle_score == 81
    assert prediction_repo.created_prediction.score_band == "good"
    assert prediction_repo.created_prediction.score_p_low == Decimal("0.01000")
    assert prediction_repo.created_prediction.score_p_high == Decimal("0.50000")
    assert prediction_repo.created_prediction.score_cohort_age == "72"
    assert prediction_repo.created_prediction.score_cohort_version == "knhanes2022_2024_v1"
    # 서버 보관 최소화(#408): 조회에 필요한 성별만 컬럼으로 남기고 **입력 원본은 저장하지 않는다.**
    #   원본을 예측마다 복제하면 프로필 컬럼을 아무리 줄여도 노출면이 그대로다.
    assert prediction_repo.created_prediction.score_cohort_sex == 1
    assert prediction_repo.created_prediction.input_snapshot is None
    # SHAP 기여도(#406)는 **저장하지 않는다**(리뷰 P1 — 파생값도 x=mean+std×(effect/-coef)로 허리둘레
    #   원본이 역산돼 #408 최소화를 무력화). 저장 컬럼 자체가 없다.
    assert not hasattr(prediction_repo.created_prediction, "score_contributions")
    # 대신 이번 재계산 응답에만 실어 내려준다(recalculated=True). 노출 3개(근력·걷기·허리)만.
    assert response.recalculated is True
    assert {c.feature for c in response.contributions} == {"musc_days", "walk_days", "waist_cm"}
    # 응답의 profile_id 도 사용자가 입력한 프로필을 가리킨다 — 재평가본이 더는 생기지 않는다.
    assert response.profile_id == 55
    assert response.prediction_id == 90
    assert response.muscle_score == 81
    assert response.score_band == "good"
    assert response.cohort_version == "knhanes2022_2024_v1"
    assert response.activity_input_source == ActivityInputSource.SERVICE_LOG
    assert session.committed is True


# ---------------- 하루 1회 재평가 정책(#388) ----------------


def test_next_reassess_available_at_is_next_kst_midnight() -> None:
    # 앱이 "내일 다시 계산할 수 있어요" 안내에 쓰는 값 — 다음 KST 자정이어야 한다.
    nxt = next_reassess_available_at()
    assert nxt.date() == today_kst() + timedelta(days=1)
    assert (nxt.hour, nxt.minute, nxt.second) == (0, 0, 0)
    assert nxt.tzinfo is not None


async def test_reassess_returns_existing_prediction_when_already_done_today() -> None:
    """오늘 이미 재평가했으면 새로 계산·저장하지 않고 그 예측을 그대로 돌려준다(#388).

    연타·재설치·API 직접 호출 어느 경로로도 같은 날 예측/프로필 행이 늘지 않아야 한다 —
    추이 그래프가 오늘 찍은 점으로 덮이는 문제를 서버에서 원천 차단하는 것이 정책의 목적이다.
    """
    existing = RiskPrediction(
        prediction_id=77,
        user_id=1,
        profile_id=72,
        model_version="test",
        model_variant=ModelVariant.WITH_WAIST,
        internal_risk_score=Decimal("0.420"),
        internal_risk_level=RiskLevel.MEDIUM,
        muscle_score=81,
        score_band="good",
        score_cohort_version="knhanes2022_2024_v1",
    )

    class _ProfileRepo:
        def __init__(self) -> None:
            self.create_calls = 0

        async def get_latest_profile(self, user_id: int) -> HealthProfile:
            return HealthProfile(
                profile_id=55,
                user_id=1,
                session_id=None,
                birth_date=date(1958, 3, 1),
                sex=Sex.MALE,
                height_cm=Decimal("160.00"),
                weight_kg=Decimal("58.00"),
                bmi=Decimal("22.7"),
                waist_cm=Decimal("82.00"),
                walk_days=5,
                musc_days=0,
                activity_input_source=ActivityInputSource.SELF_REPORT,
                activity_window_days=None,
                kidney_status=KidneyStatus.NONE,
                protein_restriction_status=ProteinRestrictionStatus.NONE,
                protein_challenge_allowed=True,
                input_method=InputMethod.FORM,
                has_estimated_value=False,
            )

        async def create_profile(self, profile: HealthProfile) -> HealthProfile:
            self.create_calls += 1
            return profile

    class _PredictionRepo:
        def __init__(self) -> None:
            self.create_calls = 0
            self.locked_user: int | None = None

        async def lock_user_for_reassess(self, user_id: int) -> None:
            self.locked_user = user_id

        async def get_today_reassessment(self, user_id: int) -> RiskPrediction:
            return existing

        async def create_risk_prediction(self, prediction: RiskPrediction) -> RiskPrediction:
            self.create_calls += 1
            return prediction

    service = RiskPredictionService(session=None)  # type: ignore[arg-type]
    profile_repo = _ProfileRepo()
    prediction_repo = _PredictionRepo()
    service.profile_repo = profile_repo  # type: ignore[assignment]
    service.prediction_repo = prediction_repo  # type: ignore[assignment]

    response = await service.reassess_latest_profile(
        cast(User, SimpleNamespace(user_id=1)),
        RiskPredictionReassessRequest(activity_window_days=7),
    )

    assert response.prediction_id == 77  # 기존 예측 그대로
    assert response.recalculated is False  # 앱이 "오늘은 이미 계산했어요"를 안내할 수 있다
    # 재계산하지 않았으므로 기여도는 빈 목록 — 앱은 이걸 받아도 기존 로컬 캐시를 지우지 않는다(#406).
    assert response.contributions == []
    assert response.next_available_at is not None
    assert prediction_repo.create_calls == 0  # 예측 행이 늘지 않는다
    assert profile_repo.create_calls == 0  # 프로필 이력도 늘지 않는다(#388 결정 4)
    assert prediction_repo.locked_user == 1  # 판정 전에 사용자 행을 잠근다(리뷰 P1)


# ---------------- 코호트 성별 계약(#408 리뷰 P1) ----------------


def test_snapshot_sex_accepts_only_model_encoding() -> None:
    """저장하는 성별은 모델 인코딩(male=1, female=2)만 허용한다.

    코호트표 키가 이 값이라, 다른 숫자가 저장되면 표에 없는 키가 되어 조회가 실패한다.
    원본 스냅샷을 지운 뒤에는 되살릴 근거가 없으므로(#408) 애초에 넣지 않는다.
    """
    assert COHORT_SEX_CODES == {1, 2}
    assert _snapshot_sex({"sex": 1}) == 1
    assert _snapshot_sex({"sex": 2}) == 2


def test_snapshot_sex_rejects_out_of_contract_values() -> None:
    # 0 은 예전 수동 검증에서 정상처럼 쓰였지만 모델 인코딩이 아니다(리뷰 지적).
    assert _snapshot_sex({"sex": 0}) is None
    assert _snapshot_sex({"sex": 3}) is None
    # 1.5 → 1 같은 절삭을 허용하면 잘못된 코호트를 조용히 고른다.
    assert _snapshot_sex({"sex": 1.5}) is None
    assert _snapshot_sex({"sex": 1.0}) is None
    assert _snapshot_sex({"sex": "1"}) is None
    # bool 은 int 하위형이라 True 가 1 로 새지 않는지 확인한다.
    assert _snapshot_sex({"sex": True}) is None
    assert _snapshot_sex({"sex": None}) is None
    assert _snapshot_sex({}) is None
    assert _snapshot_sex(None) is None


# ---------------- SHAP 기여도 계산/전달 계약(#406, #408 병행) ----------------
# 기여도는 **저장하지 않는다.** 파생값도 x = mean + std × (effect / -coef) 로 허리둘레 원본이
#   역산돼(리뷰 P1, round(,4)로 ±0.005cm 사실상 무손실) #408 최소화를 무력화하기 때문이다.
#   예측(create)·재계산 시점에 예측기가 **점수와 같은 모델 번들로** 한 번 계산해 result 에 실어주고,
#   서비스는 그걸 응답에 전달만 한다(리뷰 P1 — 서비스가 전역 아티팩트를 재선택하지 않는다).
#   /me/latest·recalculated=False 는 빈 목록으로 내려간다(앱이 prediction_id 기준 로컬 캐시로 막대를 그린다).


def test_contributions_transfers_predictor_output_without_recomputing() -> None:
    """서비스 ``_contributions`` 는 예측기가 준 기여도를 DTO 로 **변환만** 한다(#406 리뷰 P1).

    서비스가 스냅샷에서 다시 계산하면 점수를 낸 모델과 다른(전역 기본) 아티팩트를 고를 수 있어,
    점수와 막대가 다른 모델에서 나온다. 그래서 계산은 예측기 몫이고 서비스는 전달만 한다.
    """
    contribs = RiskPredictionService._contributions(
        (
            FeatureContribution(feature="waist_cm", effect_on_score_log_odds=-1.29),
            FeatureContribution(feature="musc_days", effect_on_score_log_odds=-0.17),
        )
    )
    assert [(c.feature, c.effect_on_score_log_odds) for c in contribs] == [
        ("waist_cm", -1.29),
        ("musc_days", -0.17),
    ]


def test_contributions_empty_when_predictor_provides_none() -> None:
    # 예측기가 기여도를 안 실어주면(조회 경로·계산 실패) 빈 목록 — 가짜 막대를 만들지 않는다.
    assert RiskPredictionService._contributions(None) == []
    assert RiskPredictionService._contributions(()) == []


async def test_create_prediction_delivers_predictor_contributions_to_response() -> None:
    """create 응답이 예측기가 실어준 기여도를 그대로 전달한다(#406 리뷰 P2 — 최초 공급 경로 고정).

    앱은 이 응답의 기여도를 prediction_id 기준으로 로컬 캐시해 대시보드 막대를 그린다.
    서비스가 전달을 빠뜨리면 최초 예측 후 카드가 비므로 서비스 경로에서 고정한다.
    """
    profile = HealthProfile(
        profile_id=55, user_id=1, session_id=10, birth_date=date(1958, 3, 1), sex=Sex.MALE,
        height_cm=Decimal("168.00"), weight_kg=Decimal("78.00"), bmi=Decimal("27.6"),
        waist_cm=Decimal("98.00"), walk_days=1, musc_days=0,
        activity_input_source=ActivityInputSource.SELF_REPORT, activity_window_days=None,
        kidney_status=KidneyStatus.NONE, protein_restriction_status=ProteinRestrictionStatus.NONE,
        protein_challenge_allowed=True, input_method=InputMethod.FORM, has_estimated_value=False,
    )

    class _Predictor:
        async def predict(self, features: object) -> object:
            return SimpleNamespace(
                model_version="test", model_variant=ModelVariant.WITH_WAIST,
                risk_score=0.42, risk_level=RiskLevel.MEDIUM, muscle_score=81, score_band="good",
                score_p_low=0.01, score_p_high=0.50, score_cohort_age="72",
                score_cohort_version="knhanes2022_2024_v1",
                input_snapshot={"age": 72.0, "sex": 1, "waist_cm": 98.0, "walk_days": 1.0, "musc_days": 0.0},
                score_contributions=(
                    FeatureContribution(feature="waist_cm", effect_on_score_log_odds=-1.29),
                    FeatureContribution(feature="musc_days", effect_on_score_log_odds=-0.17),
                    FeatureContribution(feature="walk_days", effect_on_score_log_odds=-0.13),
                ),
            )

    class _ProfileRepo:
        async def get_profile(self, profile_id: int, user_id: int) -> HealthProfile:
            return profile

    class _PredictionRepo:
        async def create_risk_prediction(self, prediction: RiskPrediction) -> RiskPrediction:
            prediction.prediction_id = 90
            return prediction

    session = SimpleNamespace()

    async def commit() -> None:
        return None

    async def refresh(instance: object) -> None:
        return None

    session.commit = commit
    session.refresh = refresh

    service = RiskPredictionService(session=None, predictor=_Predictor())  # type: ignore[arg-type]
    service.session = session  # type: ignore[assignment]
    service.profile_repo = _ProfileRepo()  # type: ignore[assignment]
    service.prediction_repo = _PredictionRepo()  # type: ignore[assignment]

    user = SimpleNamespace(user_id=1, onboarding_status=OnboardingStatus.PROFILE_REQUIRED)
    response = await service.create_prediction(
        cast(User, user),
        SimpleNamespace(profile_id=55),  # type: ignore[arg-type]
    )

    # 예측기가 준 3개가 순서·값 그대로 응답에 전달된다(서비스가 재계산하지 않는다).
    assert [(c.feature, c.effect_on_score_log_odds) for c in response.contributions] == [
        ("waist_cm", -1.29),
        ("musc_days", -0.17),
        ("walk_days", -0.13),
    ]
    assert response.onboarding_status == OnboardingStatus.COMPLETED.value


def test_prediction_row_has_no_contributions_column() -> None:
    """기여도는 예측 행에 저장되지 않는다 — 역산 가능한 허리둘레 복제를 두지 않기 위해서다(리뷰 P1).

    저장 컬럼이 되살아나면(누가 다시 추가하면) 이 계약이 깨지므로 모델 속성 부재로 못박는다.
    """
    prediction = RiskPrediction(
        prediction_id=1, user_id=1, profile_id=1, model_version="v1",
        model_variant=ModelVariant.WITH_WAIST, internal_risk_score=Decimal("0.12"),
        internal_risk_level=RiskLevel.LOW,
    )
    assert not hasattr(prediction, "score_contributions")


# ---------------- 기준 변경 사유(#389 B·C) ----------------


def _pred(
    prediction_id: int,
    *,
    variant: ModelVariant = ModelVariant.MINIMAL,
    cohort: str | None = "knhanes2022_2024_v1",
    profile_id: int = 1,
    model_version: str = "awgs2025-v2",
) -> RiskPrediction:
    # 이 테스트가 쓰는 필드만 채운 가짜 예측. 호출부마다 type: ignore 를 흩뿌리지 않도록
    #   여기서 한 번만 cast 한다(다중 행 호출에서는 ignore 가 인자 줄에 걸려야 해 지저분해진다).
    return cast(
        RiskPrediction,
        SimpleNamespace(
            prediction_id=prediction_id,
            created_at=datetime(2026, 7, prediction_id, 12, 0, 0),
            internal_risk_level=RiskLevel.MEDIUM,
            internal_risk_score=Decimal("0.300"),
            model_version=model_version,
            model_variant=variant,
            score_cohort_version=cohort,
            profile_id=profile_id,
        ),
    )


def _profile(
    profile_id: int,
    *,
    weight: str = "63.00",
    waist: str | None = "82.00",
    walk_days: int = 3,
) -> HealthProfile:
    """비교 대상이 되는 신체 정보만 채운 가짜 프로필.

    walk_days 는 비교 키에 **들어가지 않아야** 한다 — 레거시 재평가 사본이 이 값만 바꿔 놓았다.
    """
    return cast(
        HealthProfile,
        SimpleNamespace(
            profile_id=profile_id,
            birth_date=date(1958, 3, 1),
            sex=Sex.MALE,
            height_cm=Decimal("168.00"),
            weight_kg=Decimal(weight),
            waist_cm=None if waist is None else Decimal(waist),
            kidney_status=KidneyStatus.NONE,
            protein_restriction_status=ProteinRestrictionStatus.NONE,
            walk_days=walk_days,
        ),
    )


def test_baseline_change_reason_is_none_for_first_prediction() -> None:
    item = RiskPredictionService._to_history_item(_pred(1))

    assert item.baseline_change_reason is None
    assert item.profile_changed is False


def test_baseline_change_reason_reports_waist_added_and_removed() -> None:
    # 허리둘레 유무가 모델 번들을 가른다 — 사용자가 체감하는 '허리둘레를 넣었다/뺐다'가 곧 경계의 원인이다.
    added = RiskPredictionService._to_history_item(
        _pred(2, variant=ModelVariant.WITH_WAIST),
        _pred(1, variant=ModelVariant.MINIMAL),
    )
    removed = RiskPredictionService._to_history_item(
        _pred(2, variant=ModelVariant.MINIMAL),
        _pred(1, variant=ModelVariant.WITH_WAIST),
    )

    assert added.baseline_change_reason == BaselineChangeReason.WAIST_ADDED
    assert removed.baseline_change_reason == BaselineChangeReason.WAIST_REMOVED


def test_baseline_change_reason_reports_cohort_update_when_variant_is_same() -> None:
    item = RiskPredictionService._to_history_item(
        _pred(2, cohort="knhanes2022_2024_v2"),
        _pred(1, cohort="knhanes2022_2024_v1"),
    )

    assert item.baseline_change_reason == BaselineChangeReason.COHORT_UPDATED


def test_baseline_change_reason_stays_silent_when_cohort_version_is_unknown() -> None:
    # 코호트 컬럼 도입 이전 행은 버전이 없다 — 모르는 것을 '갱신됐다'고 말하면 안 된다.
    item = RiskPredictionService._to_history_item(
        _pred(2, cohort="knhanes2022_2024_v1"),
        _pred(1, cohort=None),
    )

    assert item.baseline_change_reason is None


def test_baseline_change_reason_is_none_when_nothing_changed() -> None:
    item = RiskPredictionService._to_history_item(_pred(2), _pred(1))

    assert item.baseline_change_reason is None
    assert item.profile_changed is False


def test_baseline_change_reason_ignores_scaffold_transitions() -> None:
    # 스캐폴드가 한쪽에 끼면 '허리둘레를 넣었다/뺐다'가 아니라 모델 자체가 교체된 것이라
    #   사유를 단정하면 거짓말이 된다(리뷰). MINIMAL <-> WITH_WAIST 쌍일 때만 사유를 낸다.
    scaffold_to_waist = RiskPredictionService._to_history_item(
        _pred(2, variant=ModelVariant.WITH_WAIST),
        _pred(1, variant=ModelVariant.RULE_BASED_SCAFFOLD),
    )
    waist_to_scaffold = RiskPredictionService._to_history_item(
        _pred(2, variant=ModelVariant.RULE_BASED_SCAFFOLD),
        _pred(1, variant=ModelVariant.WITH_WAIST),
    )
    scaffold_to_minimal = RiskPredictionService._to_history_item(
        _pred(2, variant=ModelVariant.MINIMAL),
        _pred(1, variant=ModelVariant.RULE_BASED_SCAFFOLD),
    )

    assert scaffold_to_waist.baseline_change_reason is None
    assert waist_to_scaffold.baseline_change_reason is None
    assert scaffold_to_minimal.baseline_change_reason is None


def test_profile_changed_marks_edited_body_info() -> None:
    # 체중만 고치면 모델 변형도 코호트도 그대로라 경계·사유가 없지만 점수는 달라진다 —
    #   그 변화를 활동 탓으로 단정하지 않으려면 앱이 이 신호를 봐야 한다(#389 문제 2).
    profiles = {55: _profile(55, weight="63.00"), 77: _profile(77, weight="66.00")}

    item = RiskPredictionService._to_history_item(
        _pred(2, profile_id=77),
        _pred(1, profile_id=55),
        profiles,
    )

    assert item.profile_changed is True
    assert item.baseline_change_reason is None


def test_profile_changed_is_false_for_legacy_reassessment_copies() -> None:
    """#416 이전 재평가가 만든 사본은 신체값이 같다 — 사용자가 고친 게 아니다(리뷰 지적 1).

    그 42행은 의도적으로 보존했으므로 과거 구간에서 계속 나타난다. profile_id 만 비교하면
    사용자가 아무것도 안 고친 구간이 전부 '신체 정보가 바뀌어서'로 설명된다 — 이 필드가
    막으려던 바로 그 오인이다.
    """
    # 활동 일수만 다른 복제본(레거시 재평가가 실제로 만든 모양).
    profiles = {
        55: _profile(55, walk_days=3),
        90: _profile(90, walk_days=6),
    }

    item = RiskPredictionService._to_history_item(
        _pred(2, profile_id=90),
        _pred(1, profile_id=55),
        profiles,
    )

    assert item.profile_changed is False


def test_profile_changed_is_false_when_profile_rows_are_missing() -> None:
    # 프로필을 못 읽으면(삭제·조회 실패) 단정하지 않는다 — 틀린 설명보다 중립 문구가 낫다.
    item = RiskPredictionService._to_history_item(
        _pred(2, profile_id=77),
        _pred(1, profile_id=55),
        {},
    )

    assert item.profile_changed is False


def test_profile_changed_detects_waist_entry() -> None:
    # 허리둘레를 처음 넣으면 신체 정보 변경이자 기준 변경이다 — 둘 다 참일 수 있다.
    profiles = {55: _profile(55, waist=None), 77: _profile(77, waist="82.00")}

    item = RiskPredictionService._to_history_item(
        _pred(2, profile_id=77, variant=ModelVariant.WITH_WAIST),
        _pred(1, profile_id=55, variant=ModelVariant.MINIMAL),
        profiles,
    )

    assert item.profile_changed is True
    assert item.baseline_change_reason == BaselineChangeReason.WAIST_ADDED

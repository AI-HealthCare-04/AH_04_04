from datetime import date, datetime, timedelta
from decimal import Decimal
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.core.utils.clock import today_kst
from app.dtos.risk_prediction import (
    CareStage,
    RiskComparisonStatus,
    RiskPredictionCreateResponse,
    RiskPredictionReassessRequest,
)
from app.ml.predictor import AgeNotSupportedError
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
    _serialize_contributions,
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
        # 기여도 없는 예측(구행·스냅샷 없음)은 빈 목록(#406) — 계약을 깨지 않는다.
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
            internal_risk_level=RiskLevel.MEDIUM,
            internal_risk_score=Decimal("0.281"),
            model_version="awgs2025-v2",
            model_variant=ModelVariant.MINIMAL,
        ),
        SimpleNamespace(
            prediction_id=12,
            created_at=datetime(2026, 7, 10, 12, 0, 0),
            internal_risk_level=RiskLevel.MEDIUM,
            internal_risk_score=Decimal("0.324"),
            model_version="awgs2025-v2",
            model_variant=ModelVariant.MINIMAL,
        ),
        SimpleNamespace(
            prediction_id=11,
            created_at=datetime(2026, 7, 9, 12, 0, 0),
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
    service = RiskPredictionService(session=None)  # type: ignore[arg-type]
    service.prediction_repo = repo  # type: ignore[assignment]
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
        async def predict(self, features: object) -> object:
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
                input_snapshot={"age": 72.0, "sex": 1, "bmi": 22.7, "waist_cm": 82.0},
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
    assert profile_repo.created_profile.walk_days == 5
    assert profile_repo.created_profile.musc_days == 2
    assert profile_repo.created_profile.has_estimated_value is True
    assert dashboard_repo.called_with is not None
    assert dashboard_repo.called_with[0] == 1
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
    # SHAP 기여도(#406)는 원본이 사라지기 전 예측 시점 입력으로 계산해 **파생값만** 컬럼에 남긴다.
    #   원본(input_snapshot)은 None 이어도 기여도는 유지된다 — 이게 응답 시점 재계산과의 결정적 차이다.
    stored_contribs = prediction_repo.created_prediction.score_contributions
    assert stored_contribs, "신규 예측은 파생 SHAP 기여도를 저장해야 한다"
    assert {c["feature"] for c in stored_contribs} <= {"musc_days", "walk_days", "waist_cm"}
    assert {c.feature for c in response.contributions} == {c["feature"] for c in stored_contribs}
    assert response.profile_id == 72
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


# ---------------- SHAP 기여도 저장/조회 계약(#406, #408 병행) ----------------


def test_serialize_contributions_writes_only_derived_whitelist() -> None:
    """예측 시점 입력으로 파생 기여도만 만들어 저장 형태로 직렬화한다.

    원본이 아니라 노출 3개(근력·걷기·허리)의 방향 기여만 남는다 — 최소화(#408)를 위반하지 않는다.
    """
    snapshot = {"age": 72.0, "sex": 1, "bmi": 27.6, "height_cm": 168.0, "weight_kg": 78.0,
                "waist_cm": 98.0, "walk_days": 1.0, "musc_days": 0.0}
    stored = _serialize_contributions(snapshot)
    assert stored is not None
    features = {row["feature"] for row in stored}
    assert features == {"musc_days", "walk_days", "waist_cm"}
    # 원본 식별 항목(나이·성별·키·체중·BMI)은 절대 저장되지 않는다.
    assert features.isdisjoint({"age", "sex", "height_cm", "weight_kg", "bmi"})
    assert all(isinstance(row["effect_on_score"], float) for row in stored)


def test_serialize_contributions_empty_when_no_input() -> None:
    # 입력이 없으면(구행 재계산 등) None 을 저장해 가짜 막대를 만들지 않는다.
    assert _serialize_contributions(None) is None
    assert _serialize_contributions({}) is None


def test_contributions_reads_stored_derived_even_without_snapshot() -> None:
    """응답은 저장된 파생값을 그대로 읽는다 — 원본(input_snapshot)이 없어도 유지된다.

    이것이 #410(원본 폐기)과 충돌하지 않는 핵심이다. 응답 시점에 재계산하지 않는다.
    """
    prediction = RiskPrediction(
        prediction_id=1, user_id=1, profile_id=1, model_version="v1",
        model_variant=ModelVariant.WITH_WAIST, internal_risk_score=Decimal("0.12"),
        internal_risk_level=RiskLevel.LOW, input_snapshot=None,
        score_contributions=[{"feature": "waist_cm", "effect_on_score": -1.29},
                             {"feature": "musc_days", "effect_on_score": -0.17}],
    )
    result = RiskPredictionService._contributions(prediction)
    assert [(c.feature, c.effect_on_score) for c in result] == [
        ("waist_cm", -1.29), ("musc_days", -0.17)
    ]


def test_contributions_empty_for_legacy_rows_and_bad_shapes() -> None:
    # 0021 이전 구행(컬럼 NULL)·형태가 어긋난 항목은 빈 목록으로 안전하게 넘긴다(신규 건만 제공).
    def _pred(contribs: object) -> RiskPrediction:
        return RiskPrediction(
            prediction_id=1, user_id=1, profile_id=1, model_version="v1",
            model_variant=ModelVariant.MINIMAL, internal_risk_score=Decimal("0.12"),
            internal_risk_level=RiskLevel.LOW, score_contributions=contribs,  # type: ignore[arg-type]
        )

    assert RiskPredictionService._contributions(_pred(None)) == []
    assert RiskPredictionService._contributions(_pred([])) == []
    # 잘못된 항목은 조용히 버리고, 유효한 항목만 남긴다.
    mixed = [{"feature": "walk_days", "effect_on_score": 0.2}, {"feature": 3}, "nope", {"effect_on_score": 1}]
    kept = RiskPredictionService._contributions(_pred(mixed))
    assert [(c.feature, c.effect_on_score) for c in kept] == [("walk_days", 0.2)]

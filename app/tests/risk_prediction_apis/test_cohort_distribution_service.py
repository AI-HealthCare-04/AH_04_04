"""또래 분포 차트(#193) 서비스: 코호트 선택(단일나이·80+·65 미만 422)·lower_count·density.

코호트·확률 정합(리뷰 #301): 코호트 키는 최신 프로필이 아니라 **예측이 저장한 조회 키**
(score_cohort_age · score_cohort_sex · model_variant)로 만든다. 프로필 수정·생일 경계에도
백분위·model_version 이 어긋나지 않는다.
(#408 로 입력 원본 스냅샷 보관을 없애면서, 조회에 필요한 두 값만 컬럼으로 승격했다.)
"""

from datetime import date
from decimal import Decimal
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi import HTTPException

from app.core.utils.clock import today_kst
from app.ml.predictor import _cohort_age_key, load_cohort_distribution
from app.models.enums import ModelVariant, Sex
from app.models.users import User
from app.services.risk_prediction import RiskPredictionService, _format_cohort_age_label

PREDICTION_PROFILE_ID = 10


def _profile(age_years: int, *, sex: Sex = Sex.MALE, waist_cm: float | None = None) -> SimpleNamespace:
    # 1월 1일생으로 잡아 계산 나이가 정확히 age_years 가 되게 한다.
    birth = date(today_kst().year - age_years, 1, 1)
    return SimpleNamespace(
        birth_date=birth,
        sex=sex,
        height_cm=170.0,
        weight_kg=68.0,
        bmi=23.5,
        waist_cm=waist_cm,
        walk_days=3,
        musc_days=1,
    )


def _prediction(
    probability: str = "0.05",
    *,
    variant: ModelVariant = ModelVariant.MINIMAL,
    model_version: str = "test-model-v1",
    profile_id: int = PREDICTION_PROFILE_ID,
    age: float = 72.0,
    sex: int = 1,
) -> SimpleNamespace:
    # 조회 키는 예측이 저장한 컬럼에서 온다(#408 — 입력 원본은 더 이상 보관하지 않는다).
    #   나이 키는 normalize_features 의 80 top-coding 이후 값으로 만들어진다.
    return SimpleNamespace(
        internal_risk_score=Decimal(probability),
        model_variant=variant,
        model_version=model_version,
        profile_id=profile_id,
        score_cohort_age=_cohort_age_key(min(age, 80.0)),
        score_cohort_sex=sex,
    )


def _service(
    profile: object | None,
    prediction: object | None,
    *,
    latest_profile: object | None = None,
) -> RiskPredictionService:
    """profile=예측 당시 프로필(get_profile), latest_profile=이후 수정된 최신 프로필(get_latest_profile)."""
    service = RiskPredictionService(session=None)  # type: ignore[arg-type]

    async def _get_profile(profile_id: int, user_id: int) -> object | None:
        assert profile_id == PREDICTION_PROFILE_ID  # 예측 당시 프로필로 조회해야 한다(리뷰 #301).
        return profile

    async def _get_latest_profile(user_id: int) -> object | None:
        return latest_profile if latest_profile is not None else profile

    async def _get_prediction(user_id: int) -> object | None:
        return prediction

    service.profile_repo.get_profile = _get_profile  # type: ignore[assignment]
    service.profile_repo.get_latest_profile = _get_latest_profile  # type: ignore[assignment]
    service.prediction_repo.get_latest_prediction = _get_prediction  # type: ignore[assignment]
    return service


def _run(service: RiskPredictionService):  # type: ignore[no-untyped-def]
    import asyncio

    return asyncio.run(service.get_cohort_distribution(cast(User, SimpleNamespace(user_id=1))))


def test_returns_quantiles_density_and_lower_count() -> None:
    service = _service(_profile(72, sex=Sex.MALE), _prediction("0.05"))
    resp = _run(service)
    assert resp.sex == "male"
    assert 1 <= resp.lower_count <= 99
    assert len(resp.quantiles) == 101
    assert len(resp.density) > 0
    assert all(0.0 <= y <= 100.0 for _, y in resp.density)
    assert resp.age_label.endswith("세")
    assert resp.cohort_version is not None
    assert 0.0 <= resp.probability <= 1.0


def test_lower_count_clamped_to_1_and_99() -> None:
    # 매우 낮은 확률 → 백분위 0 → 1 로 클램프. 매우 높은 확률 → 100 → 99.
    low = _service(_profile(72), _prediction("0.0"))
    assert _run(low).lower_count == 1
    high = _service(_profile(72), _prediction("0.99"))
    assert _run(high).lower_count == 99


def test_selects_80plus_pool_for_age_over_80() -> None:
    resp = _run(_service(_profile(85), _prediction("0.05", age=85.0)))
    assert resp.age_label == "80세 이상"


def test_rejects_under_65_with_422() -> None:
    service = _service(_profile(60), _prediction("0.05", age=60.0))
    with pytest.raises(HTTPException) as exc:
        _run(service)
    assert exc.value.status_code == 422


def test_missing_profile_404() -> None:
    with pytest.raises(HTTPException) as exc:
        _run(_service(None, _prediction("0.05")))
    assert exc.value.status_code == 404


def test_missing_prediction_404() -> None:
    with pytest.raises(HTTPException) as exc:
        _run(_service(_profile(72), None))
    assert exc.value.status_code == 404


def test_uses_prediction_time_profile_and_variant_after_profile_edit() -> None:
    """불일치 시나리오(리뷰 #301): 예측(minimal, 72세) 후 최신 프로필에 허리둘레·나이가 수정돼도
    코호트는 예측 당시 프로필×model_variant(minimal, 72) 키를 유지해야 한다."""
    prediction_time_profile = _profile(72, sex=Sex.MALE, waist_cm=None)
    edited_latest_profile = _profile(85, sex=Sex.MALE, waist_cm=88.0)  # 예전 코드라면 with_waist·80+ 를 고른다.
    service = _service(
        prediction_time_profile,
        _prediction("0.05", variant=ModelVariant.MINIMAL, model_version="minimal-v3"),
        latest_profile=edited_latest_profile,
    )
    resp = _run(service)
    expected = load_cohort_distribution()[("minimal", 1, _cohort_age_key(72.0))]
    assert resp.quantiles == list(expected.quantiles)
    assert resp.density == [list(point) for point in expected.density if point[0] <= 0.5]
    assert len(resp.density) == 51
    assert resp.density[-1][0] == 0.5
    assert all(point[0] <= 0.5 for point in resp.density)
    assert resp.density_method == "boundary_reflected_gaussian_kde_v1"
    assert resp.age_label != "80세 이상"
    assert resp.model_version == "minimal-v3"


def test_with_waist_prediction_uses_with_waist_cohort_and_prediction_model_version() -> None:
    """with_waist 예측은 with_waist 코호트와 비교하고, model_version 은 산출물 meta(minimal 버전)가
    아니라 실제 예측의 버전을 그대로 노출한다(리뷰 #301)."""
    service = _service(
        _profile(72, sex=Sex.FEMALE, waist_cm=85.0),
        _prediction("0.05", variant=ModelVariant.WITH_WAIST, model_version="with-waist-v3", sex=2),
    )
    resp = _run(service)
    expected = load_cohort_distribution()[("with_waist", 2, _cohort_age_key(72.0))]
    assert resp.quantiles == list(expected.quantiles)
    assert resp.model_version == "with-waist-v3"


def test_pins_snapshot_age_across_birthday_boundary() -> None:
    """생일 경계(리뷰 #301 2차): 예측 후 생일이 지나 프로필 재계산 나이가 73이어도, 확률은 72세 입력으로
    만든 값이므로 스냅샷 나이 72의 코호트와 비교해야 한다."""
    profile_now_73 = _profile(73)  # 1월 1일생 → 오늘 기준 계산 나이 73
    service = _service(profile_now_73, _prediction("0.05", age=72.0))
    resp = _run(service)
    expected = load_cohort_distribution()[("minimal", 1, _cohort_age_key(72.0))]
    assert resp.quantiles == list(expected.quantiles)
    assert resp.age_label == _format_cohort_age_label("72", expected.window)


def test_no_cross_feature_set_fallback(monkeypatch: pytest.MonkeyPatch) -> None:
    """with_waist 예측인데 with_waist 코호트가 없으면 minimal 로 폴백하지 않고 404 — 다른 모델의
    분포에 확률을 대입한 잘못된 백분위를 내지 않는다(리뷰 #301)."""
    table = load_cohort_distribution()
    minimal_only = {key: dist for key, dist in table.items() if key[0] == "minimal"}
    monkeypatch.setattr("app.services.risk_prediction.load_cohort_distribution", lambda: minimal_only)
    service = _service(
        _profile(72, waist_cm=85.0),
        _prediction("0.05", variant=ModelVariant.WITH_WAIST),
    )
    with pytest.raises(HTTPException) as exc:
        _run(service)
    assert exc.value.status_code == 404


def test_format_age_label() -> None:
    assert _format_cohort_age_label("80+", "80+") == "80세 이상"
    assert _format_cohort_age_label("72", "69-75") == "69–75세"
    assert _format_cohort_age_label("72", None) == "72세"

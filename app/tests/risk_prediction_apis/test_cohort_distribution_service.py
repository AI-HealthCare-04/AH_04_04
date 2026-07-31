"""또래 분포 차트(#193) 서비스: 코호트 선택(단일나이·80+·65 미만 422)·lower_count·density."""

from datetime import date
from decimal import Decimal
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi import HTTPException

from app.core.utils.clock import today_kst
from app.models.enums import Sex
from app.models.users import User
from app.services.risk_prediction import RiskPredictionService, _format_cohort_age_label


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


def _service(profile: object | None, prediction: object | None) -> RiskPredictionService:
    service = RiskPredictionService(session=None)  # type: ignore[arg-type]

    async def _get_profile(user_id: int) -> object | None:
        return profile

    async def _get_prediction(user_id: int) -> object | None:
        return prediction

    service.profile_repo.get_latest_profile = _get_profile  # type: ignore[assignment]
    service.prediction_repo.get_latest_prediction = _get_prediction  # type: ignore[assignment]
    return service


def _run(service: RiskPredictionService):  # type: ignore[no-untyped-def]
    import asyncio

    return asyncio.run(service.get_cohort_distribution(cast(User, SimpleNamespace(user_id=1))))


def test_returns_quantiles_density_and_lower_count() -> None:
    service = _service(_profile(72, sex=Sex.MALE), SimpleNamespace(internal_risk_score=Decimal("0.05")))
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
    low = _service(_profile(72), SimpleNamespace(internal_risk_score=Decimal("0.0")))
    assert _run(low).lower_count == 1
    high = _service(_profile(72), SimpleNamespace(internal_risk_score=Decimal("0.99")))
    assert _run(high).lower_count == 99


def test_selects_80plus_pool_for_age_over_80() -> None:
    resp = _run(_service(_profile(85), SimpleNamespace(internal_risk_score=Decimal("0.05"))))
    assert resp.age_label == "80세 이상"


def test_rejects_under_65_with_422() -> None:
    service = _service(_profile(60), SimpleNamespace(internal_risk_score=Decimal("0.05")))
    with pytest.raises(HTTPException) as exc:
        _run(service)
    assert exc.value.status_code == 422


def test_missing_profile_404() -> None:
    with pytest.raises(HTTPException) as exc:
        _run(_service(None, SimpleNamespace(internal_risk_score=Decimal("0.05"))))
    assert exc.value.status_code == 404


def test_missing_prediction_404() -> None:
    with pytest.raises(HTTPException) as exc:
        _run(_service(_profile(72), None))
    assert exc.value.status_code == 404


def test_format_age_label() -> None:
    assert _format_cohort_age_label("80+", "80+") == "80세 이상"
    assert _format_cohort_age_label("72", "69-75") == "69–75세"
    assert _format_cohort_age_label("72", None) == "72세"

# =====================================================================================
# #422 활동 일수 반영 시작일(온보딩 8일차) — 순수 로직 검증.
#
# 왜 게이트가 필요한가: 활동 창이 7일인데 온보딩 직후에는 그 창을 채울 기록이 없다. 실기록으로
#   세면 "걷기 주 5일"이라 답한 사용자가 이튿날 재평가를 눌렀을 때 walk_days=0 이 되어 점수가
#   급락한다. 사용자는 아무것도 안 했는데 떨어진 그래프를 보게 되고, 원인도 활동 부족이 아니다.
#   그래서 8일차 전에는 온보딩 자가응답 값을 그대로 쓴다(= activity_override 가 None).
#
# 경계가 핵심이라 7일차/8일차를 각각 못박는다 — 완료일 당일이 1일차다.
# =====================================================================================
from datetime import date, timedelta
from types import SimpleNamespace
from typing import cast

import pytest

from app.core.utils.clock import today_kst
from app.models.enums import ActivityType
from app.models.users import User
from app.services.risk_prediction import ACTIVITY_REFLECTION_START_DAY, RiskPredictionService


def _walking_logs(days: int) -> list[object]:
    """days 일 동안 매일 30분 걷기 — 세면 walk_days 가 그만큼 나온다."""
    return [
        SimpleNamespace(
            activity_date=today_kst() - timedelta(days=offset),
            activity_type=ActivityType.WALKING,
            duration_min=30,
            reps=None,
            sets=None,
        )
        for offset in range(days)
    ]


def _service(completed_on: date | None, logs: list[object] | None = None) -> RiskPredictionService:
    service = RiskPredictionService(session=None, predictor=None)  # type: ignore[arg-type]

    class _ProfileRepo:
        async def get_onboarding_completed_on(self, user_id: int) -> date | None:
            return completed_on

    class _DashboardRepo:
        async def get_activity_logs_between(self, user_id: int, start: date, end: date) -> list[object]:
            return logs or []

    service.profile_repo = _ProfileRepo()  # type: ignore[assignment]
    service.dashboard_repo = _DashboardRepo()  # type: ignore[assignment]
    return service


_USER = cast(User, SimpleNamespace(user_id=1))


@pytest.mark.asyncio
async def test_before_day_eight_keeps_self_reported_days() -> None:
    """7일차까지는 None — 호출부가 프로필의 자가응답 값을 그대로 쓴다."""
    # 완료 당일(1일차). 기록이 있어도 세지 않는다.
    assert await _service(today_kst(), _walking_logs(1))._derive_activity_days(_USER, 7) is None

    # 7일차 = 완료 후 6일. 아직 창이 온보딩 이전 기간을 포함한다.
    seventh_day = today_kst() - timedelta(days=ACTIVITY_REFLECTION_START_DAY - 2)
    assert await _service(seventh_day, _walking_logs(6))._derive_activity_days(_USER, 7) is None


@pytest.mark.asyncio
async def test_from_day_eight_counts_real_logs() -> None:
    """8일차부터 실기록을 센다 — 창이 온보딩 이후 기록으로 가득 찬 첫날이다."""
    eighth_day = today_kst() - timedelta(days=ACTIVITY_REFLECTION_START_DAY - 1)
    result = await _service(eighth_day, _walking_logs(5))._derive_activity_days(_USER, 7)
    assert result is not None
    walk_days, musc_days = result
    assert walk_days == 5  # 30분 이상 걸은 날 5일
    assert musc_days == 0  # 근력 기록은 없다


@pytest.mark.asyncio
async def test_missing_onboarding_date_keeps_self_reported_days() -> None:
    """완료일을 모르면 세지 않는다 — 기준이 없는 상태에서 0 으로 떨어뜨리지 않는다."""
    assert await _service(None, _walking_logs(7))._derive_activity_days(_USER, 7) is None


@pytest.mark.asyncio
async def test_no_logs_after_day_eight_counts_zero() -> None:
    """8일차 이후 기록이 없으면 0 이다 — 게이트가 푼 뒤에는 실기록이 그대로 반영된다(A안)."""
    eighth_day = today_kst() - timedelta(days=ACTIVITY_REFLECTION_START_DAY - 1)
    assert await _service(eighth_day, [])._derive_activity_days(_USER, 7) == (0, 0)

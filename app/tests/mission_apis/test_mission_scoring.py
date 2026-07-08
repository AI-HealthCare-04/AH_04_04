# =====================================================================================
# Mission 판정 "순수 로직" 단위테스트 (DB 불필요).
# 센서/미션 판정 로직을 서비스에서 분리해뒀기 때문에, DB 없이 빠르게 검증 가능합니다.
# =====================================================================================
from app.models.enums import DailyResult
from app.services.mission_scoring import (
    DAILY_GREAT_SUCCESS_THRESHOLD,
    DAILY_SUCCESS_THRESHOLD,
    compute_daily_result,
    compute_earned_points,
)


def test_daily_result_none_when_no_counted_mission() -> None:
    assert compute_daily_result(0) == DailyResult.NONE


def test_daily_result_success_at_threshold() -> None:
    assert compute_daily_result(DAILY_SUCCESS_THRESHOLD) == DailyResult.SUCCESS


def test_daily_result_great_success_at_threshold() -> None:
    assert compute_daily_result(DAILY_GREAT_SUCCESS_THRESHOLD) == DailyResult.GREAT_SUCCESS
    assert compute_daily_result(DAILY_GREAT_SUCCESS_THRESHOLD + 5) == DailyResult.GREAT_SUCCESS


def test_earned_points_zero_when_not_success() -> None:
    assert compute_earned_points(success=False, reward_points=10) == 0


def test_earned_points_reward_when_success() -> None:
    assert compute_earned_points(success=True, reward_points=10) == 10

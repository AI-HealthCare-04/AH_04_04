# =====================================================================================
# Mission 판정 "순수 로직" 단위테스트 (DB 불필요).
# 센서/미션 판정 로직을 서비스에서 분리해뒀기 때문에, DB 없이 빠르게 검증 가능합니다.
# =====================================================================================
from app.models.enums import DailyResult, MissionType
from app.services.mission_scoring import (
    DAILY_GREAT_SUCCESS_THRESHOLD,
    DAILY_SUCCESS_THRESHOLD,
    compute_daily_result,
    compute_earned_points,
    is_all_missions_complete,
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


# ---------------- 모든 미션 완료 보너스 판정 ----------------

_ALL_FOUR = {MissionType.WALKING, MissionType.EXERCISE, MissionType.MEAL, MissionType.GAME}
# 신장질환·투석·단백질 제한 사용자: 식사 미션이 목록에서 빠진다.
_KIDNEY_RESTRICTED = {MissionType.WALKING, MissionType.EXERCISE, MissionType.GAME}


def test_bonus_when_every_visible_mission_is_done() -> None:
    assert is_all_missions_complete(_ALL_FOUR, _ALL_FOUR) is True


def test_no_bonus_when_one_mission_left() -> None:
    assert is_all_missions_complete(_ALL_FOUR, _ALL_FOUR - {MissionType.GAME}) is False


def test_kidney_restricted_user_gets_bonus_without_meal() -> None:
    # 식사 미션이 애초에 안 보이는 사용자 → 나머지 3종만 채워도 '모두 완료'다.
    #   4종 고정으로 판정하면 이 사용자는 보너스를 영원히 못 받는다(이 테스트가 그 회귀를 막는다).
    assert is_all_missions_complete(_KIDNEY_RESTRICTED, _KIDNEY_RESTRICTED) is True


def test_no_bonus_when_nothing_is_visible() -> None:
    # 템플릿 미시드 등으로 보이는 미션이 없으면, 아무것도 안 해도 참이 되지 않아야 한다.
    assert is_all_missions_complete(set(), set()) is False


def test_extra_completed_types_do_not_break_the_check() -> None:
    # 숨겨진 식사 미션을 어떻게든 완료한 경우처럼 '보이는 것보다 더' 채운 날도 보너스는 준다.
    assert is_all_missions_complete(_KIDNEY_RESTRICTED, _ALL_FOUR) is True

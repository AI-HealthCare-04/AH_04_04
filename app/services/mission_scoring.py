# =====================================================================================
# Mission 판정 "순수 로직" 모음.
# DB나 세션에 의존하지 않는 순수 함수만 둡니다. → DB 없이 단위 테스트 가능.
# (Service는 이 함수들을 조합해서 씁니다.)
#
# daily_result 임계값은 팀 결정으로 확정됨: 카운트된 미션 1개 이상=성공, 3개 이상=대성공.
#   근거: 수행 카테고리는 최대 4개(걷기·운동·식사·게임)이므로 대성공(3)은 "4개 중 3개"로
#         도달 가능하면서 절반 이상을 요구한다. (운동은 '운동하기' 단일 미션으로 통합됨)
# =====================================================================================
from app.models.enums import DailyResult

DAILY_SUCCESS_THRESHOLD = 1  # 카운트된 미션 1개 이상 → 성공
DAILY_GREAT_SUCCESS_THRESHOLD = 3  # 3개 이상 → 대성공


def compute_daily_result(counted_mission_count: int) -> DailyResult:
    """하루 동안 '카운트된 미션 수'로 성공/대성공을 판정한다."""
    if counted_mission_count >= DAILY_GREAT_SUCCESS_THRESHOLD:
        return DailyResult.GREAT_SUCCESS
    if counted_mission_count >= DAILY_SUCCESS_THRESHOLD:
        return DailyResult.SUCCESS
    return DailyResult.NONE


def compute_earned_points(success: bool, reward_points: int) -> int:
    """성공한 경우에만 템플릿의 보상 포인트를 지급한다."""
    return reward_points if success else 0

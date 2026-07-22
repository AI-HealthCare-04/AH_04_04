# =====================================================================================
# Mission 도메인 DTO (요청/응답 스키마) — API 명세서 v7.1 기준
#   - GET  /missions            : 수행 가능한 미션 목록
#   - POST /mission-logs        : 미션 로그 생성 (운동/걷기 시작 or 식사/게임 즉시완료)
#   - PATCH /mission-logs/{id}  : 미션 로그 수정 (운동 완료 / 걷기 종료)
#   - GET  /mission-logs        : 미션 로그 조회 (일자별)
# 값 검증은 Pydantic이 자동으로 해줍니다. enum 값은 v7.1 명세와 동일하게 맞춥니다.
# =====================================================================================
from datetime import datetime

from pydantic import BaseModel, Field

from app.dtos.base import BaseSerializerModel
from app.models.enums import (
    ActivityType,
    GameType,
    InputMethod,
    Intensity,
    MissionStatus,
    MissionType,
    PerceivedDifficulty,
    TargetUnit,
)

# -------------------------------------------------------------------------------------
# [GET /missions] 응답
# -------------------------------------------------------------------------------------


class MissionResponse(BaseSerializerModel):
    mission_template_id: int
    mission_type: str
    title: str
    description: str | None
    level: str
    # 응답 필드명은 target_value 이지만, ORM 컬럼은 default_target_value 이므로 alias로 매핑한다.
    target_value: int = Field(validation_alias="default_target_value")
    target_unit: str
    requires_safety_notice: bool
    daily_count_limit: int | None
    reward_points: int


class MissionListResponse(BaseModel):
    missions: list[MissionResponse]


# -------------------------------------------------------------------------------------
# [POST /mission-logs] 요청
#   status=in_progress : 운동/걷기 "시작" (안전 고지 확인값만)
#   status=completed   : 식사/게임 "즉시완료" (수행값 + 상세)
# -------------------------------------------------------------------------------------


class MealDetail(BaseModel):
    protein_foods: list[str]
    protein_meal_count: int
    raw_text: str | None = None


class GameDetail(BaseModel):
    game_type: GameType
    score: int | None = None
    duration_sec: int | None = None
    success_count: int | None = None
    mistake_count: int | None = None
    completed: bool = False


class MissionLogCreateRequest(BaseModel):
    mission_template_id: int
    mission_type: MissionType
    status: MissionStatus  # in_progress | completed (skipped는 여기서 안 씀)

    # status=in_progress (운동/걷기 시작)
    safety_notice_confirmed: bool | None = None
    safety_notice_confirmed_at: datetime | None = None

    # status=completed (식사/게임 즉시완료)
    actual_value: float | None = None
    target_value: float | None = None
    target_unit: TargetUnit | None = None
    success: bool | None = None
    input_method: InputMethod | None = None
    created_on_device_at: datetime | None = None
    meal_detail: MealDetail | None = None
    game_detail: GameDetail | None = None


class MissionLogCreateResponse(BaseModel):
    mission_log_id: int
    status: str
    success: bool
    counted_for_daily: bool
    daily_limit_reached: bool  # 식사 1일 1회 초과 시 true (팝업 근거)
    earned_points: int
    daily_result: str  # none | success | great_success
    # 같은 수행이 이미 기록돼 있어 새로 만들지 않고 기존 것을 돌려줬는가(#91).
    #   오프라인 outbox 가 응답을 못 받고 재전송한 경우다. true 면 HTTP 200(생성 아님)으로 응답한다.
    #   앱은 이 값을 보고 "전송 성공"으로 처리하고 outbox 에서 제거하면 된다.
    deduplicated: bool = False


# -------------------------------------------------------------------------------------
# [PATCH /mission-logs/{id}] 요청 — 운동 완료 / 걷기 종료
# -------------------------------------------------------------------------------------


class ExerciseDetail(BaseModel):
    activity_type: ActivityType | None = None
    intensity: Intensity | None = None
    reps: int | None = None
    sets: int | None = None
    # 걷기(WalkingDetail)와 같은 경계 방어. 운동 성공도 '당일 누적 시간'으로 판정하게 되면서,
    #   음수 시간을 보내 누적을 되돌려 '하루 1회' 적립을 우회하는 길이 여기에도 열린다.
    #   미측정(None)은 허용하되 0 이하는 거부한다.
    duration_min: float | None = Field(default=None, gt=0)
    met_value: float | None = None


class WalkingDetail(BaseModel):
    # 경계 입력 방어(지영 #65 재리뷰): 음수 시간으로 당일 누적을 되돌려 '하루 1회' 적립을
    #   우회하는 것을 차단한다. 걷기 구간은 양수 시간이어야 하고, 걸음·거리는 음수 불가.
    duration_min: float = Field(gt=0)
    distance_km: float | None = Field(default=None, ge=0)
    steps: int | None = Field(default=None, ge=0)


class MissionLogUpdateRequest(BaseModel):
    status: MissionStatus = MissionStatus.COMPLETED

    # 운동 완료
    actual_value: float | None = None
    target_value: float | None = None
    target_unit: TargetUnit | None = None
    success: bool | None = None
    input_method: InputMethod | None = None
    manual_override: bool = False
    perceived_difficulty: PerceivedDifficulty | None = None
    pain_reported: bool = False
    dizziness_reported: bool = False
    exercise_detail: ExerciseDetail | None = None

    # 걷기 종료
    walking_detail: WalkingDetail | None = None


class MissionLogUpdateResponse(BaseModel):
    mission_log_id: int
    status: str
    # 걷기·운동: 같은 날 자동 합산 시간(달성 판정 기준). 앱이 "10분 중 4분" 진행을 보여줄 수 있다.
    daily_total_min: float | None = None
    daily_total_steps: int | None = None  # 걷기: 같은 날 자동 합산 걸음수(표시 전용)
    success: bool
    counted_for_daily: bool
    daily_result: str
    sync_status: str


# -------------------------------------------------------------------------------------
# [GET /mission-logs] 응답 (일자별)
# -------------------------------------------------------------------------------------


class MissionLogListItem(BaseModel):
    mission_log_id: int
    mission_type: str
    success: bool
    counted_for_daily: bool
    earned_points: int


class MissionLogListResponse(BaseModel):
    logs: list[MissionLogListItem]

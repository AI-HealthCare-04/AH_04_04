# =====================================================================================
# Mission 도메인 DTO (요청/응답 스키마) — API 명세서 v7.1 기준
#   - GET  /missions            : 수행 가능한 미션 목록
#   - POST /mission-logs        : 미션 로그 생성 (운동/걷기 시작 or 식사/게임 즉시완료)
#   - PATCH /mission-logs/{id}  : 미션 로그 수정 (운동 완료 / 걷기 종료)
#   - GET  /mission-logs        : 미션 로그 조회 (일자별)
# 값 검증은 Pydantic이 자동으로 해줍니다. enum 값은 v7.1 명세와 동일하게 맞춥니다.
# =====================================================================================
from pydantic import BaseModel, Field

from app.dtos.base import BaseSerializerModel, KstDatetime, KstNaiveDatetime
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


class MealTodayLog(BaseModel):
    """단백질 미션의 '오늘 기록' — 재진입 시 앱이 카드 선택 상태를 복원하는 데 쓴다(지시서 §3-3, §4.1)."""

    eaten: list[str]
    logged_at: KstDatetime


class MissionTodayProgress(BaseModel):
    """운동·걷기 미션의 '오늘 누적 진행'. 재생/측정을 안 해도, 목록 진입 시점에 '오늘까지 얼마나 했는지'를
    보여주려는 값이다(완료 응답의 daily_total_min과 같은 서버 당일 합산 권위값을 목록에도 노출).

    total_min 은 오늘 누적 시간(분), total_steps 는 걷기 전용 누적 걸음(표시용, 운동은 null),
    goal_reached 는 목표(target_value, 분) 도달 여부다. 아직 오늘 한 게 없으면 total_min=0 으로 내려간다."""

    total_min: float
    total_steps: int | None = None
    goal_reached: bool


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
    # 단백질(식사) 미션 한정: 오늘 저장된 기록. 없으면 null. 다른 종류 미션은 항상 null.
    today_log: MealTodayLog | None = None
    # 운동·걷기 미션 한정: 오늘 누적 진행(분·걸음·목표달성). 다른 종류 미션은 항상 null.
    today_progress: MissionTodayProgress | None = None
    # 게임 미션 한정(#346): 오늘 이미 완료했는지(하루 1회 상한 #272 의 counted 기준). 다른 종류는 항상 null.
    #   1회성 미션이라 진행바 대신 '오늘 했음' 배지를 그리는 데 쓴다.
    today_done: bool | None = None


class MissionListResponse(BaseModel):
    missions: list[MissionResponse]


# -------------------------------------------------------------------------------------
# [POST /mission-logs] 요청
#   status=in_progress : 운동/걷기 "시작" (안전 고지 확인값만)
#   status=completed   : 식사/게임 "즉시완료" (수행값 + 상세)
# -------------------------------------------------------------------------------------


class MealDetail(BaseModel):
    protein_foods: list[str]
    raw_text: str | None = None


class GameDetail(BaseModel):
    game_type: GameType
    score: int | None = Field(default=None, ge=0)
    duration_sec: int | None = Field(default=None, ge=0)
    success_count: int | None = Field(default=None, ge=0)
    mistake_count: int | None = Field(default=None, ge=0)
    completed: bool = False


class MissionLogCreateRequest(BaseModel):
    mission_template_id: int
    mission_type: MissionType
    status: MissionStatus  # in_progress | completed (skipped는 여기서 안 씀)

    # status=in_progress (운동/걷기 시작)
    safety_notice_confirmed: bool | None = None
    safety_notice_confirmed_at: KstNaiveDatetime | None = None

    # status=completed (식사/게임 즉시완료)
    actual_value: float | None = Field(default=None, ge=0)
    target_value: float | None = Field(default=None, ge=0)
    target_unit: TargetUnit | None = None
    success: bool | None = None
    input_method: InputMethod | None = None
    created_on_device_at: KstNaiveDatetime | None = None
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
    reps: int | None = Field(default=None, ge=0)
    sets: int | None = Field(default=None, ge=0)
    # 걷기(WalkingDetail)와 같은 경계 방어. 운동 성공도 '당일 누적 시간'으로 판정하게 되면서,
    #   음수 시간을 보내 누적을 되돌려 '하루 1회' 적립을 우회하는 길이 여기에도 열린다.
    #   미측정(None)은 허용하되 0 이하는 거부한다.
    # 상한(le=1440=24h): duration_min 은 DB physical_activity_logs.Numeric(6,2)(≤9999.99)에 저장되므로
    #   상한이 없으면 과대값이 파싱 통과 후 저장 시 DataError(500) 로 터진다. 근본 방어를 DTO 경계에 둔다.
    duration_min: float | None = Field(default=None, gt=0, le=1440)
    met_value: float | None = Field(default=None, ge=0)


class WalkingDetail(BaseModel):
    # 경계 입력 방어(지영 #65 재리뷰): 음수 시간으로 당일 누적을 되돌려 '하루 1회' 적립을
    #   우회하는 것을 차단한다. 걷기 구간은 양수 시간이어야 하고, 걸음·거리는 음수 불가.
    # 상한(le=1440=24h): ExerciseDetail 과 동일 — Numeric(6,2) 저장 오버플로(500) 를 DTO 경계에서 막는다.
    duration_min: float = Field(gt=0, le=1440)
    distance_km: float | None = Field(default=None, ge=0)
    steps: int | None = Field(default=None, ge=0)


class MissionLogUpdateRequest(BaseModel):
    status: MissionStatus = MissionStatus.COMPLETED

    # 운동 완료
    actual_value: float | None = Field(default=None, ge=0)
    target_value: float | None = Field(default=None, ge=0)
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
    # 기록 탭 달력·일별 추이(#기록탭 §5.1/§5.2)용으로 추가: 미션명 + 완료(기록) 시각(KST).
    title: str
    completed_at: KstDatetime
    success: bool
    counted_for_daily: bool
    earned_points: int


class MissionLogListResponse(BaseModel):
    logs: list[MissionLogListItem]

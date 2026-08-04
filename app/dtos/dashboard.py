from datetime import date

from pydantic import BaseModel, Field

from app.models.enums import DailyResult


class DashboardPredictionInputs(BaseModel):
    # 근감소증 예측 대시보드(#193) 초기값 — 등록된 사용자 데이터로 대시보드를 개인화한다.
    #   신체 값은 최신 health_profile 에서, 운동 요일 수는 최근 7일 daily_activity_summaries 에서 파생.
    #   온보딩 미완(프로필 없음)이면 신체 값은 null → 앱이 대시보드 기본값으로 폴백한다.
    sex: str | None = None            # "male" | "female"
    birth_date: date | None = None
    height_cm: float | None = None
    weight_kg: float | None = None
    waist_cm: float | None = None     # 미측정이면 null → 허리 제외형(minimal) 모델
    walk_days: int = Field(ge=0, le=7)  # 최근 7일 '걷기' 성공 요일 수(챌린지 기록 파생, 0~7)
    musc_days: int = Field(ge=0, le=5)  # 최근 7일 '운동' 성공 요일 수(0~5)


class HomeUser(BaseModel):
    nickname: str


class PointBalanceResponse(BaseModel):
    current_points: int


class HomeLatestPrediction(BaseModel):
    care_stage: str
    display_message: str


class HomeTodaySummary(BaseModel):
    counted_mission_count: int
    daily_result: DailyResult


class HomeAvailableMissionSummary(BaseModel):
    meal: int
    exercise: int
    walking: int
    game: int


# [응답] 홈 "오늘 걷기" 위젯 — 당일 누적 실적(권위값은 서버).
#   daily_total_min  : 오늘 누적 걷기 시간(분). 달성 판정 기준(≥ 목표 분).
#   daily_total_steps: 오늘 누적 걸음. 표시 전용(판정에는 안 씀).
#   걷기 안 한 날도 {0, 0}로 내려 앱 바인딩을 단순화한다(null 아님).
#   ⚠️ 목표(분)는 여기 넣지 않는다 — 목표는 GET /missions(걷기 target_value)만이 단일 원천.
#      (/home·/missions 두 곳에 목표를 두면 값이 어긋날 위험 → 실적은 /home, 목표는 /missions로 역할 분리.)
class HomeTodayWalking(BaseModel):
    daily_total_min: float
    daily_total_steps: int


class HomeStreak(BaseModel):
    # 스트릭은 서버가 KST 날짜와 성공한 미션 기록으로 계산한 권위값이다.
    # 앱은 이 값을 자체 계산하거나 낙관적으로 증가시키지 않는다.
    current_days: int = Field(ge=0)
    completed_today: bool
    as_of_date: date


# [응답] 홈 통합 조회 (명세 §29). 홈 화면에 필요한 정보를 한 번에 반환한다.
# latest_prediction은 nullable(건강체크 건너뛰면 null).
class HomeResponse(BaseModel):
    user: HomeUser
    point_balance: PointBalanceResponse
    latest_prediction: HomeLatestPrediction | None
    today_summary: HomeTodaySummary
    available_mission_summary: HomeAvailableMissionSummary
    today_walking: HomeTodayWalking
    streak: HomeStreak


# [응답] 월별 스탬프 요약 (명세 §30). 조회 월의 일자별 성취(daily_activity_summaries)를 반환한다.
# 활동이 있는 날(요약 행이 있는 날)만 담고, 나머지는 프론트가 none으로 처리한다.
class StampDay(BaseModel):
    date: date
    daily_result: DailyResult
    counted_mission_count: int
    earned_points: int


class StampsResponse(BaseModel):
    month: str
    days: list[StampDay]


# [응답] 걷기 일별 막대(#기록탭 §5.3). 최근 N일 걷기 걸음·분. 걷기 없는 날도 0으로 채워 내려준다.
class WalkingDayPoint(BaseModel):
    date: date
    steps: int = Field(ge=0)
    minutes: float = Field(ge=0)


class WalkingDailyResponse(BaseModel):
    days: list[WalkingDayPoint]


# [응답] 챌린지 유형별 '완료 일수' 도넛(#기록탭 §5.4 선호도). 모든 유형 하루 1회 상한. 0회 유형도 포함(범례 회색).
class ChallengeTypeTotal(BaseModel):
    mission_type: str  # walking | exercise | meal | game
    count: int = Field(ge=0)  # 해당 유형을 완료한 일수


class ChallengeTotalsResponse(BaseModel):
    total: int = Field(ge=0)  # 유형별 완료 일수 합(도넛 중앙 '누적 N회')
    by_type: list[ChallengeTypeTotal]


# [응답] what-if 점수 시뮬레이션(#기록탭 §4). 걷기/근력 일수를 0→N 으로 바꿨을 때의 근육 건강 점수.
#   코호트 분위수표 미도착·65세 미만이면 각 지점 score=null(앱이 "준비 중").
class ScoreSimPoint(BaseModel):
    days: int = Field(ge=0)
    score: int | None = Field(default=None, ge=0, le=100)


class ScoreSimulationResponse(BaseModel):
    walk: list[ScoreSimPoint]  # 걷기 0~7일
    musc: list[ScoreSimPoint]  # 근력 0~5일
    cohort_version: str | None = None

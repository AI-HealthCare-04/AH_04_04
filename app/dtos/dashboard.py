from datetime import date

from pydantic import BaseModel, Field

from app.dtos.base import KstDatetime
from app.dtos.risk_prediction import RiskComparisonStatus
from app.models.enums import ActivityLevel, DailyResult


class HomeUser(BaseModel):
    nickname: str


class PointBalanceResponse(BaseModel):
    current_points: int


# GET /users/me/points — 포인트 잔액 + 적립 이력.
# earn_logs 항목 계약(명세). 적립 이력 테이블(point_earn_logs)은 MVP 이후로 미뤄져 있어
#   현재는 항상 빈 배열로 응답한다(테이블 도입 시 채운다).
class PointEarnLogItem(BaseModel):
    earn_id: int
    earned_points: int
    reason: str
    created_at: KstDatetime


class PointsResponse(BaseModel):
    current_points: int
    earn_logs: list[PointEarnLogItem]


class HomeActivityProfile(BaseModel):
    current_level: ActivityLevel


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


# [응답] 홈 통합 조회 (명세 §29). 홈 화면에 필요한 정보를 한 번에 반환한다.
# latest_prediction은 nullable(건강체크 건너뛰면 null).
class HomeResponse(BaseModel):
    user: HomeUser
    point_balance: PointBalanceResponse
    activity_profile: HomeActivityProfile
    latest_prediction: HomeLatestPrediction | None
    today_summary: HomeTodaySummary
    available_mission_summary: HomeAvailableMissionSummary
    today_walking: HomeTodayWalking


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


# [응답] 대시보드 시각화 (GET /dashboard/summary). 최근 days일 구간의 활동 추이·생활기록·위험도 변화.
class ActivityTrendPoint(BaseModel):
    date: date
    moderate_equivalent_min: float


class LifestyleRecords(BaseModel):
    meal_days: int
    game_count: int


class RiskChangePoint(BaseModel):
    at: KstDatetime
    risk_score: float = Field(ge=0, le=1)
    change_percentage_points: float | None = Field(ge=-100, le=100)
    comparison_status: RiskComparisonStatus
    # 기존 Android 호환 필드. 연속형 화면 전환 뒤 제거 또는 내부 한정 예정이다.
    care_stage: str


class DashboardSummaryResponse(BaseModel):
    range_days: int
    baseline_date: date
    total_moderate_equivalent_min: float
    activity_trend: list[ActivityTrendPoint]
    lifestyle_records: LifestyleRecords
    # 위험도 변화 추이(오래된→최신). 예측 이력이 없으면 빈 배열.
    risk_change: list[RiskChangePoint]

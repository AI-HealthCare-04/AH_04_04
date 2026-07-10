from datetime import date, datetime

from pydantic import BaseModel

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
    created_at: datetime


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


# [응답] 홈 통합 조회 (명세 §29). 홈 화면에 필요한 정보를 한 번에 반환한다.
# latest_prediction은 nullable(건강체크 건너뛰면 null).
class HomeResponse(BaseModel):
    user: HomeUser
    point_balance: PointBalanceResponse
    activity_profile: HomeActivityProfile
    latest_prediction: HomeLatestPrediction | None
    today_summary: HomeTodaySummary
    available_mission_summary: HomeAvailableMissionSummary


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
    at: datetime
    care_stage: str


class DashboardSummaryResponse(BaseModel):
    range_days: int
    baseline_date: date
    total_moderate_equivalent_min: float
    activity_trend: list[ActivityTrendPoint]
    lifestyle_records: LifestyleRecords
    # risk_change는 예측 이력(지영님 도메인)이 준비되면 채운다. 현재는 빈 배열.
    risk_change: list[RiskChangePoint]

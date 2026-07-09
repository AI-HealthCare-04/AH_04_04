from pydantic import BaseModel

from app.models.enums import ActivityLevel, DailyResult


class HomeUser(BaseModel):
    nickname: str


class PointBalanceResponse(BaseModel):
    current_points: int


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

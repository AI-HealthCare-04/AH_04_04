import calendar
import re
from datetime import date

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.dashboard import (
    HomeActivityProfile,
    HomeAvailableMissionSummary,
    HomeLatestPrediction,
    HomeResponse,
    HomeTodaySummary,
    HomeUser,
    PointBalanceResponse,
    PointsResponse,
    StampDay,
    StampsResponse,
)
from app.models.enums import ActivityLevel, DailyResult, MissionType
from app.models.users import User
from app.repositories.activity_profile_repository import ActivityProfileRepository
from app.repositories.dashboard_repository import DashboardRepository
from app.services.mission import MissionService
from app.services.risk_prediction import RiskPredictionService


class DashboardService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = DashboardRepository(session)
        self.activity_repo = ActivityProfileRepository(session)
        self.mission_service = MissionService(session)
        self.risk_service = RiskPredictionService(session)

    async def get_home(self, user: User) -> HomeResponse:
        current_points = await self.repo.get_current_points(user.user_id)
        summary = await self.repo.get_today_summary(user.user_id)
        # 사용자의 실제 운동 난이도(user_activity_profiles.current_level)를 읽어온다.
        #   프로필이 아직 없으면(건강체크 스킵/기초체력검사 전) 기본 easy로 본다.
        #   표시(activity_profile)와 미션 수 산정에 같은 레벨을 써서
        #   "표시 레벨과 카운트 레벨이 어긋나는" 불일치를 막는다(정인/지영 리뷰 반영).
        profile = await self.activity_repo.get_by_user_id(user.user_id)
        effective_level = profile.current_level if profile else ActivityLevel.EASY
        available = await self._available_mission_summary(user, effective_level)
        latest_prediction = await self._latest_prediction(user)
        return HomeResponse(
            user=HomeUser(nickname=user.nickname),
            point_balance=PointBalanceResponse(current_points=current_points),
            activity_profile=HomeActivityProfile(current_level=effective_level),
            latest_prediction=latest_prediction,
            today_summary=HomeTodaySummary(
                counted_mission_count=summary.counted_mission_count if summary else 0,
                daily_result=summary.daily_result if summary else DailyResult.NONE,
            ),
            available_mission_summary=available,
        )

    async def _latest_prediction(self, user: User) -> HomeLatestPrediction | None:
        # 예측 도메인 공개 인터페이스를 소비한다(그 서비스가 care_stage/display_message 매핑의 단일 출처).
        # 단독 조회용 메서드라 예측이 없으면 404를 던지므로, 홈에서는 이를 null로 변환한다(명세상 nullable).
        try:
            prediction = await self.risk_service.get_latest_prediction(user)
        except HTTPException as exc:
            if exc.status_code == status.HTTP_404_NOT_FOUND:
                return None
            raise
        return HomeLatestPrediction(
            care_stage=prediction.care_stage,
            display_message=prediction.display_message,
        )

    async def get_points(self, user: User) -> PointsResponse:
        # 잔액은 point_balances에서 실제 조회. 적립 이력(point_earn_logs)은 아직 미도입 테이블이라
        #   현재는 빈 배열로 응답한다(v6.0에서 사용 이력 point_spend_logs는 제거되어 노출하지 않음).
        current_points = await self.repo.get_current_points(user.user_id)
        return PointsResponse(current_points=current_points, earn_logs=[])

    async def get_stamps(self, user: User, month: str) -> StampsResponse:
        start, end = self._month_range(month)
        summaries = await self.repo.get_summaries_between(user.user_id, start, end)
        days = [
            StampDay(
                date=summary.summary_date,
                daily_result=summary.daily_result,
                counted_mission_count=summary.counted_mission_count,
                earned_points=summary.earned_points,
            )
            for summary in summaries
        ]
        return StampsResponse(month=month, days=days)

    @staticmethod
    def _month_range(month: str) -> tuple[date, date]:
        """`YYYY-MM` → 해당 월의 (1일, 말일). 형식이 잘못되면 400."""
        # `YYYY-MM` 형식을 엄격히 요구한다(예: `2026-7`은 거부).
        if not re.fullmatch(r"\d{4}-\d{2}", month):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="month 형식이 올바르지 않습니다. (YYYY-MM)",
            )
        try:
            year, month_num = (int(part) for part in month.split("-", 1))
            start = date(year, month_num, 1)
        except ValueError as exc:  # 형식은 맞지만 월 범위 밖(예: 2026-13/2026-00)
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="month 형식이 올바르지 않습니다. (YYYY-MM)",
            ) from exc
        last_day = calendar.monthrange(year, month_num)[1]
        return start, date(year, month_num, last_day)

    async def _available_mission_summary(
        self, user: User, level: ActivityLevel
    ) -> HomeAvailableMissionSummary:
        # 미션 도메인 공개 인터페이스(get_missions)를 소비해 유형별 수행 가능 미션 수를 센다.
        #   레벨을 명시해 넘겨 홈 표시 레벨과 카운트 산정 레벨을 일치시킨다.
        #   TODO: 오늘 완료분/식사 1일 1회 등 '잔여' 반영은 후속(mission 담당과 조율).
        missions = await self.mission_service.get_missions(user, mission_type=None, level=level)
        counts = dict.fromkeys(
            (MissionType.MEAL, MissionType.EXERCISE, MissionType.WALKING, MissionType.GAME), 0
        )
        for mission in missions:
            try:
                mission_type = MissionType(mission.mission_type)
            except ValueError:
                continue  # enum에 없는 타입은 방어적으로 건너뛴다(500 방지)
            counts[mission_type] += 1
        return HomeAvailableMissionSummary(
            meal=counts[MissionType.MEAL],
            exercise=counts[MissionType.EXERCISE],
            walking=counts[MissionType.WALKING],
            game=counts[MissionType.GAME],
        )

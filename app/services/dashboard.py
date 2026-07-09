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
)
from app.models.enums import ActivityLevel, DailyResult, MissionType
from app.models.users import User
from app.repositories.dashboard_repository import DashboardRepository
from app.services.mission import MissionService
from app.services.risk_prediction import RiskPredictionService


class DashboardService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = DashboardRepository(session)
        self.mission_service = MissionService(session)
        self.risk_service = RiskPredictionService(session)

    async def get_home(self, user: User) -> HomeResponse:
        current_points = await self.repo.get_current_points(user.user_id)
        summary = await self.repo.get_today_summary(user.user_id)
        available = await self._available_mission_summary(user)
        latest_prediction = await self._latest_prediction(user)
        return HomeResponse(
            user=HomeUser(nickname=user.nickname),
            point_balance=PointBalanceResponse(current_points=current_points),
            # activity_profile: activity 도메인 미구현 → 기본 난이도 easy로 채운다(건너뛴 사용자 기본 easy).
            #   TODO: activity 도메인 완성 후 사용자의 실제 current_level로 교체.
            activity_profile=HomeActivityProfile(current_level=ActivityLevel.EASY),
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

    async def _available_mission_summary(self, user: User) -> HomeAvailableMissionSummary:
        # 미션 도메인 공개 인터페이스(get_missions)를 소비해 유형별 수행 가능 미션 수를 센다.
        #   TODO: 오늘 완료분/식사 1일 1회 등 '잔여' 반영은 후속(mission 담당과 조율).
        missions = await self.mission_service.get_missions(user, mission_type=None, level=None)
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

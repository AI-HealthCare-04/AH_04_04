from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.dashboard import (
    HomeActivityProfile,
    HomeAvailableMissionSummary,
    HomeResponse,
    HomeTodaySummary,
    HomeUser,
    PointBalanceResponse,
)
from app.models.enums import ActivityLevel, DailyResult, MissionType
from app.models.users import User
from app.repositories.dashboard_repository import DashboardRepository
from app.services.mission import MissionService


class DashboardService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = DashboardRepository(session)
        self.mission_service = MissionService(session)

    async def get_home(self, user: User) -> HomeResponse:
        current_points = await self.repo.get_current_points(user.user_id)
        summary = await self.repo.get_today_summary(user.user_id)
        available = await self._available_mission_summary(user)
        return HomeResponse(
            user=HomeUser(nickname=user.nickname),
            point_balance=PointBalanceResponse(current_points=current_points),
            # activity_profile: activity 도메인 미구현 → 기본 난이도 easy로 채운다(건너뛴 사용자 기본 easy).
            #   TODO: activity 도메인 완성 후 사용자의 실제 current_level로 교체.
            activity_profile=HomeActivityProfile(current_level=ActivityLevel.EASY),
            # latest_prediction: health_profile 입력 전엔 예측이 존재하지 않아 null(명세상 nullable).
            #   TODO: health_profile 머지 후 RiskPrediction 최신값 조회(get_latest)로 교체.
            latest_prediction=None,
            today_summary=HomeTodaySummary(
                counted_mission_count=summary.counted_mission_count if summary else 0,
                daily_result=summary.daily_result if summary else DailyResult.NONE,
            ),
            available_mission_summary=available,
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

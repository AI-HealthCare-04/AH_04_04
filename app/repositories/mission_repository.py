# =====================================================================================
# Mission 도메인 Repository — DB 접근만 담당 (비즈니스 규칙은 Service에).
# 사용하는 테이블: mission_templates, mission_logs, meal_logs, game_logs,
#                  physical_activity_logs, daily_activity_summaries, user_activity_profiles
# =====================================================================================
from datetime import date

from sqlalchemy import func, select
from sqlalchemy.dialects.mysql import insert as mysql_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.activity import UserActivityProfile
from app.models.dashboard import DailyActivitySummary
from app.models.enums import (
    ActivityLevel,
    ActivityType,
    DailyResult,
    MissionType,
)
from app.models.missions import (
    GameLog,
    MealLog,
    MissionLog,
    MissionTemplate,
    PhysicalActivityLog,
)


class MissionRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    # ---------------- mission_templates ----------------

    async def get_active_templates(
        self,
        level: ActivityLevel | None = None,
        mission_type: MissionType | None = None,
    ) -> list[MissionTemplate]:
        """활성 미션 템플릿을 레벨/종류로 필터링해 display_order 순으로 반환."""
        stmt = select(MissionTemplate).where(MissionTemplate.is_active.is_(True))
        if level is not None:
            stmt = stmt.where(MissionTemplate.level == level)
        if mission_type is not None:
            stmt = stmt.where(MissionTemplate.mission_type == mission_type)
        stmt = stmt.order_by(MissionTemplate.display_order)
        result = await self.session.scalars(stmt)
        return list(result.all())

    async def get_template(self, mission_template_id: int) -> MissionTemplate | None:
        return await self.session.get(MissionTemplate, mission_template_id)

    # ---------------- user_activity_profiles (읽기 전용) ----------------

    async def get_user_current_level(self, user_id: int) -> ActivityLevel | None:
        """사용자의 현재 활동 레벨 (없으면 None). health/risk 담당 영역이라 읽기만 함."""
        stmt = select(UserActivityProfile.current_level).where(UserActivityProfile.user_id == user_id)
        return await self.session.scalar(stmt)

    # ---------------- mission_logs ----------------

    async def create_mission_log(self, mission_log: MissionLog) -> MissionLog:
        self.session.add(mission_log)
        await self.session.flush()  # PK(mission_log_id) 채우기
        return mission_log

    async def get_mission_log(self, mission_log_id: int, user_id: int) -> MissionLog | None:
        """본인 소유의 미션 로그만 조회 (남의 로그 접근 방지)."""
        stmt = select(MissionLog).where(
            MissionLog.mission_log_id == mission_log_id,
            MissionLog.user_id == user_id,
        )
        return await self.session.scalar(stmt)

    async def list_mission_logs(self, user_id: int, on_date: date | None) -> list[MissionLog]:
        stmt = select(MissionLog).where(MissionLog.user_id == user_id)
        if on_date is not None:
            stmt = stmt.where(func.date(MissionLog.created_at) == on_date)
        stmt = stmt.order_by(MissionLog.created_at.desc())
        result = await self.session.scalars(stmt)
        return list(result.all())

    # ---------------- 상세 로그 (mission_log 1:1) ----------------

    async def add_meal_log(self, meal_log: MealLog) -> None:
        self.session.add(meal_log)
        await self.session.flush()

    async def add_game_log(self, game_log: GameLog) -> None:
        self.session.add(game_log)
        await self.session.flush()

    async def add_physical_activity_log(self, activity_log: PhysicalActivityLog) -> None:
        self.session.add(activity_log)
        await self.session.flush()

    # ---------------- 집계 (일일 합산 / 식사 1일 1회) ----------------

    async def count_meal_missions_today(self, user_id: int) -> int:
        """오늘 이미 '카운트된' 식사 미션 수 (식사 1일 1회 판정용)."""
        stmt = (
            select(func.count())
            .select_from(MissionLog)
            .where(
                MissionLog.user_id == user_id,
                MissionLog.mission_type == MissionType.MEAL,
                MissionLog.counted_for_daily.is_(True),
                func.date(MissionLog.created_at) == func.current_date(),
            )
        )
        return int(await self.session.scalar(stmt) or 0)

    async def counted_breakdown_today(self, user_id: int) -> dict[MissionType, int]:
        """오늘 '카운트된' 미션을 종류별로 집계 (일일 요약의 종류별 컬럼 채우기용)."""
        stmt = (
            select(MissionLog.mission_type, func.count())
            .where(
                MissionLog.user_id == user_id,
                MissionLog.counted_for_daily.is_(True),
                func.date(MissionLog.created_at) == func.current_date(),
            )
            .group_by(MissionLog.mission_type)
        )
        rows = await self.session.execute(stmt)
        return {mission_type: int(count) for mission_type, count in rows.all()}

    async def sum_earned_points_today(self, user_id: int) -> int:
        stmt = select(func.coalesce(func.sum(MissionLog.earned_points), 0)).where(
            MissionLog.user_id == user_id,
            func.date(MissionLog.created_at) == func.current_date(),
        )
        return int(await self.session.scalar(stmt) or 0)

    async def sum_walking_minutes_today(self, user_id: int) -> float:
        """오늘 걷기 총 시간(분) — '같은 날 자동 합산'용. mission_logs와 조인."""
        stmt = (
            select(func.coalesce(func.sum(PhysicalActivityLog.duration_min), 0))
            .select_from(PhysicalActivityLog)
            .join(MissionLog, MissionLog.mission_log_id == PhysicalActivityLog.mission_log_id)
            .where(
                MissionLog.user_id == user_id,
                PhysicalActivityLog.activity_type == ActivityType.WALKING,
                PhysicalActivityLog.activity_date == func.current_date(),
            )
        )
        return float(await self.session.scalar(stmt) or 0)

    # ---------------- daily_activity_summaries upsert ----------------

    async def upsert_daily_summary(
        self,
        user_id: int,
        counted_mission_count: int,
        meal_counted: bool,
        exercise_count: int,
        walking_count: int,
        game_count: int,
        earned_points: int,
        daily_result: DailyResult,
    ) -> None:
        """오늘자(서버 기준 current_date) 요약을 (user_id, summary_date)로 원자적 upsert.

        NOT NULL 컬럼(meal_counted/exercise_count/walking_count/game_count)은 Core insert가
        ORM의 Python default를 거치지 않으므로 반드시 명시해야 INSERT가 실패하지 않는다.
        """
        stmt = mysql_insert(DailyActivitySummary).values(
            user_id=user_id,
            summary_date=func.current_date(),
            counted_mission_count=counted_mission_count,
            meal_counted=meal_counted,
            exercise_count=exercise_count,
            walking_count=walking_count,
            game_count=game_count,
            earned_points=earned_points,
            daily_result=daily_result,
        )
        stmt = stmt.on_duplicate_key_update(
            counted_mission_count=stmt.inserted.counted_mission_count,
            meal_counted=stmt.inserted.meal_counted,
            exercise_count=stmt.inserted.exercise_count,
            walking_count=stmt.inserted.walking_count,
            game_count=stmt.inserted.game_count,
            earned_points=stmt.inserted.earned_points,
            daily_result=stmt.inserted.daily_result,
        )
        await self.session.execute(stmt)
        await self.session.flush()

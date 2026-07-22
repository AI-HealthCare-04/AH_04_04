# =====================================================================================
# Mission 도메인 Repository — DB 접근만 담당 (비즈니스 규칙은 Service에).
# 사용하는 테이블: mission_templates, mission_logs, meal_logs, game_logs,
#                  physical_activity_logs, daily_activity_summaries, user_activity_profiles
# =====================================================================================
from datetime import date, datetime

from sqlalchemy import func, or_, select
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
from app.models.users import User


class MissionRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    # ---------------- mission_templates ----------------

    async def get_active_templates(
        self,
        level: ActivityLevel | None = None,
        mission_type: MissionType | None = None,
        exclude_kidney_check: bool = False,
    ) -> list[MissionTemplate]:
        """활성 미션 템플릿을 레벨/종류로 필터링해 display_order 순으로 반환.

        레벨 필터는 '걷기'에만 적용한다: 걷기만 추론 모델이 정한 난이도별
        변형(easy 20분 / normal 30분 / hard 40분)이 있고, 운동/식사/게임은
        전 레벨 공통이라 레벨과 무관하게 노출한다.
        → 걷기는 사용자당 정확히 1개. 레벨이 바뀌면 그 레벨의 걷기로 교체되어 나온다.

        exclude_kidney_check=True면 신장/단백질 제한 사용자에게 위험한
        (requires_kidney_check=True) 미션을 제외한다. (안전 필터)
        """
        stmt = select(MissionTemplate).where(MissionTemplate.is_active.is_(True))
        if level is not None:
            stmt = stmt.where(
                or_(
                    MissionTemplate.mission_type != MissionType.WALKING,
                    MissionTemplate.level == level,
                )
            )
        if mission_type is not None:
            stmt = stmt.where(MissionTemplate.mission_type == mission_type)
        if exclude_kidney_check:
            stmt = stmt.where(MissionTemplate.requires_kidney_check.is_(False))
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

    async def find_mission_log_by_device_time(
        self, user_id: int, mission_template_id: int, created_on_device_at: datetime
    ) -> MissionLog | None:
        """같은 수행이 이미 기록돼 있는지 자연 키로 조회 (오프라인 재전송 판별용, #91).

        uq_mission_logs_user_template_device_time 과 같은 조합이다.
        """
        stmt = select(MissionLog).where(
            MissionLog.user_id == user_id,
            MissionLog.mission_template_id == mission_template_id,
            MissionLog.created_on_device_at == created_on_device_at,
        )
        return await self.session.scalar(stmt)

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

    async def sum_walking_totals_today(self, user_id: int) -> tuple[float, int]:
        """오늘 걷기 누적 (분, 걸음)을 한 SELECT로 함께 반환한다. mission_logs와 조인.

        분(daily_total_min·달성 판정)과 걸음(daily_total_steps·표시)을 각각 별도 쿼리로 읽으면,
        엔진이 READ COMMITTED라 두 쿼리 사이에 걷기 완료가 커밋될 때 각 SELECT가 서로 다른
        최신 커밋을 봐서 시점이 어긋난 쌍(예: 이전 분 + 최신 걸음)이 나올 수 있다.
        한 문장(동일 statement snapshot)에서 함께 집계해, 응답이 항상 '이전 쌍' 또는
        '최신 쌍' 중 하나로만 나오게 한다(홈·걷기완료가 같은 일관된 쌍을 보장 — 지영 리뷰 #69).
        """
        stmt = (
            select(
                func.coalesce(func.sum(PhysicalActivityLog.duration_min), 0),
                func.coalesce(func.sum(PhysicalActivityLog.steps), 0),
            )
            .select_from(PhysicalActivityLog)
            .join(MissionLog, MissionLog.mission_log_id == PhysicalActivityLog.mission_log_id)
            .where(
                MissionLog.user_id == user_id,
                PhysicalActivityLog.activity_type == ActivityType.WALKING,
                PhysicalActivityLog.activity_date == func.current_date(),
            )
        )
        total_min, total_steps = (await self.session.execute(stmt)).one()
        return float(total_min or 0), int(total_steps or 0)

    async def lock_user_for_completion(self, user_id: int) -> None:
        """미션 완료 트랜잭션을 사용자 단위로 직렬화한다(동시 요청 race 방지).

        users 행을 FOR UPDATE로 잠근다. 이걸 트랜잭션의 첫 읽기로 두면 ①같은 사용자의 동시
        완료가 직렬화되고 ②locking read 이후 첫 consistent read가 잠금 획득(=선행 트랜잭션
        커밋) 이후에 스냅샷을 잡아, 걷기 누적 판정·요약 재집계가 커밋된 최신값을 본다.
        """
        await self.session.execute(select(User.user_id).where(User.user_id == user_id).with_for_update())

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

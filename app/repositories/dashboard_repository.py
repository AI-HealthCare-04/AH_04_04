# =====================================================================================
# Dashboard 도메인 Repository — 대시보드 화면용 읽기 전용 집계.
# 사용하는 테이블: daily_activity_summaries (dashboard 도메인 모델),
#   mission_logs·physical_activity_logs (미션 도메인 모델을 읽기 전용으로 소비 —
#   포인트 잔액·적립이력은 mission_logs.earned_points에서 파생, 활동량은 physical_activity_logs).
# 다른 도메인 데이터(미션/예측)는 읽기만 하고 그 도메인 파일은 수정하지 않는다.
# =====================================================================================
from datetime import date

from sqlalchemy import and_, case, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.dashboard import DailyActivitySummary
from app.models.enums import ActivityType
from app.models.missions import MissionLog, PhysicalActivityLog

# 예측 대시보드(#193) walk_days/musc_days 정의 — 모델 학습 변수와 일치시킨다.
#   walk_days = "하루 30분 이상 걷기"  → WALKING + duration_min ≥ 30
#   musc_days = "근력운동"            → SEATED/STANDING_EXERCISE (STRETCHING·WARM_UP 등 제외)
WALK_MIN_DURATION_MIN = 30
MUSC_ACTIVITY_TYPES = (ActivityType.SEATED_EXERCISE, ActivityType.STANDING_EXERCISE)


class DashboardRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_current_points(self, user_id: int) -> int:
        """포인트 잔액 = 적립 총합. v6.0에서 포인트는 순증가(사용 이력 제거)라
        mission_logs.earned_points 합계가 곧 현재 잔액이다(별도 잔액 테이블 유지 불필요)."""
        stmt = select(func.coalesce(func.sum(MissionLog.earned_points), 0)).where(MissionLog.user_id == user_id)
        return int(await self.session.scalar(stmt) or 0)

    async def get_earn_logs(self, user_id: int) -> list[MissionLog]:
        """적립 이력 = 포인트가 지급된 미션 로그(earned_points>0), 최신순."""
        stmt = (
            select(MissionLog)
            .where(MissionLog.user_id == user_id, MissionLog.earned_points > 0)
            .order_by(MissionLog.created_at.desc())
        )
        return list((await self.session.scalars(stmt)).all())

    async def get_today_summary(self, user_id: int) -> DailyActivitySummary | None:
        """오늘자(서버 current_date) 활동 요약. 미션 도메인이 upsert한 값을 읽기만 한다."""
        stmt = select(DailyActivitySummary).where(
            DailyActivitySummary.user_id == user_id,
            DailyActivitySummary.summary_date == func.current_date(),
        )
        return await self.session.scalar(stmt)

    async def count_active_days(self, user_id: int, start: date, end: date) -> tuple[int, int]:
        """[start, end](양끝 포함)의 (walk_days, musc_days). 모델 학습 변수 정의와 일치시킨다(#193, 리뷰 반영).

        원천은 `physical_activity_logs`(활동별 duration_min·activity_type 보유). 요약 테이블의
        단순 카운트로는 '30분 이상'·'근력' 정의를 복원할 수 없어 원천 로그에서 정확히 센다:
          - walk_days = WALKING & duration_min ≥ 30 인 **날 수**(distinct activity_date)
          - musc_days = SEATED/STANDING_EXERCISE 인 **날 수** (STRETCHING·WARM_UP 등 비근력 제외)
        physical_activity_logs 에는 user_id 가 없어 mission_logs 와 조인해 사용자로 거른다."""
        walk_day = case(
            (
                and_(
                    PhysicalActivityLog.activity_type == ActivityType.WALKING,
                    PhysicalActivityLog.duration_min >= WALK_MIN_DURATION_MIN,
                ),
                PhysicalActivityLog.activity_date,
            )
        )
        musc_day = case(
            (PhysicalActivityLog.activity_type.in_(MUSC_ACTIVITY_TYPES), PhysicalActivityLog.activity_date)
        )
        stmt = (
            select(func.count(func.distinct(walk_day)), func.count(func.distinct(musc_day)))
            .join(MissionLog, PhysicalActivityLog.mission_log_id == MissionLog.mission_log_id)
            .where(
                MissionLog.user_id == user_id,
                PhysicalActivityLog.activity_date >= start,
                PhysicalActivityLog.activity_date <= end,
            )
        )
        row = (await self.session.execute(stmt)).one()
        return int(row[0] or 0), int(row[1] or 0)

    async def get_summaries_between(
        self, user_id: int, start: date, end: date
    ) -> list[DailyActivitySummary]:
        """기간(start~end, 양끝 포함) 내 일자별 활동 요약을 날짜 오름차순으로 반환."""
        stmt = (
            select(DailyActivitySummary)
            .where(
                DailyActivitySummary.user_id == user_id,
                DailyActivitySummary.summary_date >= start,
                DailyActivitySummary.summary_date <= end,
            )
            .order_by(DailyActivitySummary.summary_date)
        )
        result = await self.session.scalars(stmt)
        return list(result.all())

    async def get_activity_logs_between(
        self, user_id: int, start: date, end: date
    ) -> list[PhysicalActivityLog]:
        """기간(start~end, 양끝 포함) 내 신체활동 로그를 활동량 환산 원천으로 읽는다(읽기 전용).

        physical_activity_logs에는 user_id가 없어 mission_logs와 조인해 사용자로 거른다.
        """
        stmt = (
            select(PhysicalActivityLog)
            .join(MissionLog, PhysicalActivityLog.mission_log_id == MissionLog.mission_log_id)
            .where(
                MissionLog.user_id == user_id,
                PhysicalActivityLog.activity_date >= start,
                PhysicalActivityLog.activity_date <= end,
            )
            .order_by(PhysicalActivityLog.activity_date)
        )
        result = await self.session.scalars(stmt)
        return list(result.all())

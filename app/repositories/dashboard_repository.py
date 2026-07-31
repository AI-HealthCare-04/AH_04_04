# =====================================================================================
# Dashboard 도메인 Repository — 대시보드 화면용 읽기 전용 집계.
# 사용하는 테이블: daily_activity_summaries (dashboard 도메인 모델),
#   mission_logs·physical_activity_logs (미션 도메인 모델을 읽기 전용으로 소비 —
#   포인트 잔액·적립이력은 mission_logs.earned_points에서 파생, 활동량은 physical_activity_logs).
# 다른 도메인 데이터(미션/예측)는 읽기만 하고 그 도메인 파일은 수정하지 않는다.
# =====================================================================================
from datetime import date

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.dashboard import DailyActivitySummary
from app.models.enums import ActivityType, MissionType
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
          - walk_days = 날짜별 WALKING `duration_min` **합계** ≥ 30 인 날 수
            (리뷰 반영: 같은 날 20+15분=35분도 포함, 20+9분=29분은 제외 — '하루 30분' 정의)
          - musc_days = SEATED/STANDING_EXERCISE 가 있는 날 수(distinct) (STRETCHING·WARM_UP 등 제외)
        physical_activity_logs 에는 user_id 가 없어 mission_logs 와 조인해 사용자로 거른다."""
        base_where = (
            MissionLog.user_id == user_id,
            PhysicalActivityLog.activity_date >= start,
            PhysicalActivityLog.activity_date <= end,
        )
        # walk: 날짜별 걷기시간 합산 후 ≥30분인 날. 개별 로그가 아니라 '당일 누적' 기준(#204 리뷰).
        walk_daily = (
            select(PhysicalActivityLog.activity_date)
            .join(MissionLog, PhysicalActivityLog.mission_log_id == MissionLog.mission_log_id)
            .where(*base_where, PhysicalActivityLog.activity_type == ActivityType.WALKING)
            .group_by(PhysicalActivityLog.activity_date)
            .having(func.coalesce(func.sum(PhysicalActivityLog.duration_min), 0) >= WALK_MIN_DURATION_MIN)
            .subquery()
        )
        walk_days = int(await self.session.scalar(select(func.count()).select_from(walk_daily)) or 0)
        # musc: 근력(SEATED/STANDING)이 하루에 한 번이라도 있으면 그 날 1일.
        musc_days = int(
            await self.session.scalar(
                select(func.count(func.distinct(PhysicalActivityLog.activity_date)))
                .join(MissionLog, PhysicalActivityLog.mission_log_id == MissionLog.mission_log_id)
                .where(*base_where, PhysicalActivityLog.activity_type.in_(MUSC_ACTIVITY_TYPES))
            )
            or 0
        )
        return walk_days, musc_days

    async def get_counted_summary_dates(self, user_id: int, through_date: date) -> list[date]:
        """기준일까지 성공한 미션이 한 개 이상 있는 날짜를 최신순으로 반환한다.

        날짜별 중복 제거와 성공 판정은 미션 도메인이 유지하는
        daily_activity_summaries를 단일 원천으로 사용한다.
        """
        stmt = (
            select(DailyActivitySummary.summary_date)
            .where(
                DailyActivitySummary.user_id == user_id,
                DailyActivitySummary.summary_date <= through_date,
                DailyActivitySummary.counted_mission_count > 0,
            )
            .order_by(DailyActivitySummary.summary_date.desc())
        )
        return list((await self.session.scalars(stmt)).all())

    async def get_summaries_between(self, user_id: int, start: date, end: date) -> list[DailyActivitySummary]:
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

    async def get_activity_logs_between(self, user_id: int, start: date, end: date) -> list[PhysicalActivityLog]:
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

    async def get_walking_daily(self, user_id: int, start: date, end: date) -> dict[date, tuple[int, float]]:
        """[start, end](양끝 포함) 걷기 일별 (걸음 합, 분 합)을 activity_date별로 집계한다(#기록탭 §5.3).

        원천은 `physical_activity_logs`(WALKING). 하루 여러 세션이면 SUM 으로 당일 누적한다.
        physical_activity_logs 에는 user_id 가 없어 mission_logs 와 조인해 사용자로 거른다.
        걷기 없는 날은 결과에 없으며(서비스에서 0으로 채움), 판정이 아닌 표시 전용 집계다.
        """
        stmt = (
            select(
                PhysicalActivityLog.activity_date,
                func.coalesce(func.sum(PhysicalActivityLog.steps), 0),
                func.coalesce(func.sum(PhysicalActivityLog.duration_min), 0),
            )
            .join(MissionLog, PhysicalActivityLog.mission_log_id == MissionLog.mission_log_id)
            .where(
                MissionLog.user_id == user_id,
                PhysicalActivityLog.activity_type == ActivityType.WALKING,
                PhysicalActivityLog.activity_date >= start,
                PhysicalActivityLog.activity_date <= end,
            )
            .group_by(PhysicalActivityLog.activity_date)
        )
        rows = (await self.session.execute(stmt)).all()
        return {row[0]: (int(row[1]), float(row[2])) for row in rows}

    async def get_challenge_totals(self, user_id: int) -> dict[MissionType, int]:
        """유형별 '완료한 일수'(#기록탭 §5.4 도넛 — 유형별 선호도). counted_for_daily=True 인 로그의 **완료일 distinct 수**.

        기준은 counted_for_daily(앱 전체 '미션 완료' 정의: 홈 완료 개수·포인트·#274 와 동일)이되, **모든 유형을
        하루 1회로 상한**한다 → 유형별 '며칠 했나' 선호도. 걷기·운동·식사는 이미 1일 1회(counted)라 그대로지만,
        게임은 일일 제한이 없어 하루 여러 번 counted 될 수 있어(성공마다) 완료일 distinct 로 캡한다(재란님 결정).
        완료일은 달력·일별 추이와 동일 기준: 걷기·운동은 physical_activity_logs.created_at(완료 시각),
        식사·게임(즉시완료)은 mission_logs.created_at → COALESCE 후 DATE.
        """
        completed_at = func.coalesce(PhysicalActivityLog.created_at, MissionLog.created_at)
        stmt = (
            select(MissionLog.mission_type, func.count(func.distinct(func.date(completed_at))))
            .outerjoin(PhysicalActivityLog, PhysicalActivityLog.mission_log_id == MissionLog.mission_log_id)
            .where(MissionLog.user_id == user_id, MissionLog.counted_for_daily.is_(True))
            .group_by(MissionLog.mission_type)
        )
        rows = (await self.session.execute(stmt)).all()
        return {row[0]: int(row[1]) for row in rows}

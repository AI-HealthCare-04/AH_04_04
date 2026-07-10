# =====================================================================================
# Dashboard 도메인 Repository — 대시보드 화면용 읽기 전용 집계.
# 사용하는 테이블: point_balances, daily_activity_summaries (dashboard 도메인 모델),
#   physical_activity_logs (미션 도메인 모델을 읽기 전용으로 소비 — 활동량 환산 원천).
# 다른 도메인 데이터(미션/예측)는 읽기만 하고 그 도메인 파일은 수정하지 않는다.
# =====================================================================================
from datetime import date

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.dashboard import DailyActivitySummary, PointBalance
from app.models.missions import MissionLog, PhysicalActivityLog


class DashboardRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_current_points(self, user_id: int) -> int:
        """포인트 잔액(point_balances). 행이 없으면 0."""
        stmt = select(PointBalance.current_points).where(PointBalance.user_id == user_id)
        return int(await self.session.scalar(stmt) or 0)

    async def get_today_summary(self, user_id: int) -> DailyActivitySummary | None:
        """오늘자(서버 current_date) 활동 요약. 미션 도메인이 upsert한 값을 읽기만 한다."""
        stmt = select(DailyActivitySummary).where(
            DailyActivitySummary.user_id == user_id,
            DailyActivitySummary.summary_date == func.current_date(),
        )
        return await self.session.scalar(stmt)

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

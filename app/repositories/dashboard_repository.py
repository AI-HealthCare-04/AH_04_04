# =====================================================================================
# Dashboard 도메인 Repository — 대시보드 화면용 읽기 전용 집계.
# 사용하는 테이블: point_balances, daily_activity_summaries (둘 다 dashboard 도메인 모델)
# 다른 도메인 데이터(미션/예측)는 각 도메인의 공개 인터페이스를 Service에서 소비한다.
# =====================================================================================
from datetime import date

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.dashboard import DailyActivitySummary, PointBalance


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

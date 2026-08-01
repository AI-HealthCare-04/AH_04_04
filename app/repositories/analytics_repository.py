from sqlalchemy.ext.asyncio import AsyncSession

from app.models.analytics import StsOverlayEvent, StsScoreViewEvent


class AnalyticsRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def add_sts_overlay_event(self, event: StsOverlayEvent) -> None:
        self.session.add(event)
        await self.session.flush()

    async def add_sts_score_view_event(self, event: StsScoreViewEvent) -> None:
        self.session.add(event)
        await self.session.flush()

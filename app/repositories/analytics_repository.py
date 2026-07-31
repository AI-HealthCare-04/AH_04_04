from sqlalchemy.ext.asyncio import AsyncSession

from app.models.analytics import StsOverlayEvent


class AnalyticsRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def add_sts_overlay_event(self, event: StsOverlayEvent) -> None:
        self.session.add(event)
        await self.session.flush()

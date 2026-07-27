from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.enums import HealthCheckStatus
from app.models.health import HealthCheckSession


class HealthCheckRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def create_session(self, health_check_session: HealthCheckSession) -> HealthCheckSession:
        self.session.add(health_check_session)
        await self.session.flush()
        return health_check_session

    async def get_session(self, session_id: int, user_id: int) -> HealthCheckSession | None:
        stmt = select(HealthCheckSession).where(
            HealthCheckSession.session_id == session_id,
            HealthCheckSession.user_id == user_id,
        )
        return await self.session.scalar(stmt)

    async def get_latest_started(self, user_id: int) -> HealthCheckSession | None:
        """사용자의 가장 최근 STARTED 세션(중단 후 재진입 시 재사용용, #180). 없으면 None."""
        stmt = (
            select(HealthCheckSession)
            .where(
                HealthCheckSession.user_id == user_id,
                HealthCheckSession.status == HealthCheckStatus.STARTED,
            )
            .order_by(HealthCheckSession.session_id.desc())
            .limit(1)
        )
        return await self.session.scalar(stmt)

    async def update_session(self, health_check_session: HealthCheckSession) -> HealthCheckSession:
        await self.session.flush()
        return health_check_session

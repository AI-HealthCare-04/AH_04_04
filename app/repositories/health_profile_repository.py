from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.health import HealthCheckSession, HealthProfile


class HealthProfileRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_session(self, session_id: int, user_id: int) -> HealthCheckSession | None:
        stmt = select(HealthCheckSession).where(
            HealthCheckSession.session_id == session_id,
            HealthCheckSession.user_id == user_id,
        )
        return await self.session.scalar(stmt)

    async def create_profile(self, profile: HealthProfile) -> HealthProfile:
        self.session.add(profile)
        await self.session.flush()
        return profile

    async def get_profile(self, profile_id: int, user_id: int) -> HealthProfile | None:
        stmt = select(HealthProfile).where(
            HealthProfile.profile_id == profile_id,
            HealthProfile.user_id == user_id,
        )
        return await self.session.scalar(stmt)

    async def get_latest_profile(self, user_id: int) -> HealthProfile | None:
        stmt = (
            select(HealthProfile)
            .where(HealthProfile.user_id == user_id)
            .order_by(HealthProfile.created_at.desc(), HealthProfile.profile_id.desc())
            .limit(1)
        )
        return await self.session.scalar(stmt)

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.activity import ActivityLevelChangeLog, UserActivityProfile


class ActivityProfileRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_by_user_id(self, user_id: int) -> UserActivityProfile | None:
        stmt = select(UserActivityProfile).where(UserActivityProfile.user_id == user_id)
        return await self.session.scalar(stmt)

    async def create_profile(self, profile: UserActivityProfile) -> UserActivityProfile:
        self.session.add(profile)
        await self.session.flush()
        return profile

    async def update_profile(self, profile: UserActivityProfile) -> UserActivityProfile:
        await self.session.flush()
        return profile

    async def create_level_change_log(self, log: ActivityLevelChangeLog) -> ActivityLevelChangeLog:
        self.session.add(log)
        await self.session.flush()
        return log

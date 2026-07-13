from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.settings import PersonalizedSetting


class PersonalizedSettingRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_by_user_id(self, user_id: int) -> PersonalizedSetting | None:
        return await self.session.scalar(
            select(PersonalizedSetting).where(PersonalizedSetting.user_id == user_id)
        )

    async def add(self, setting: PersonalizedSetting) -> PersonalizedSetting:
        self.session.add(setting)
        await self.session.flush()
        return setting

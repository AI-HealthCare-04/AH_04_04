from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.users import UserSettingsUpdateRequest, UserUpdateRequest
from app.models.users import User
from app.repositories.user_repository import UserRepository


class UserManageService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.user_repo = UserRepository(session)

    async def update_user(self, user: User, data: UserUpdateRequest) -> User:
        if data.nickname is not None:
            user = await self.user_repo.update_nickname(user, data.nickname)
            await self.session.commit()
            await self.session.refresh(user)
        return user


class UserSettingsService:
    async def update_settings(self, data: UserSettingsUpdateRequest) -> UserSettingsUpdateRequest:
        # Settings persistence is a next step after personalized_settings is modeled.
        return data

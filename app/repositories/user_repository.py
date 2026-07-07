from datetime import datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import config
from app.models.users import AuthProvider, OnboardingStatus, User


class UserRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_user(self, user_id: int) -> User | None:
        return await self.session.get(User, user_id)

    async def get_by_provider_social_id(self, provider: AuthProvider, social_id: str) -> User | None:
        stmt = select(User).where(
            User.provider == provider,
            User.social_id == social_id,
            User.deleted_at.is_(None),
        )
        return await self.session.scalar(stmt)

    async def create_google_user(self, social_id: str, nickname: str) -> User:
        user = User(
            provider=AuthProvider.GOOGLE,
            social_id=social_id,
            nickname=nickname,
            onboarding_status=OnboardingStatus.PENDING,
            last_login_at=datetime.now(config.TIMEZONE),
        )
        self.session.add(user)
        await self.session.flush()
        return user

    async def update_last_login(self, user: User) -> None:
        user.last_login_at = datetime.now(config.TIMEZONE)
        await self.session.flush()

    async def update_nickname(self, user: User, nickname: str) -> User:
        user.nickname = nickname
        await self.session.flush()
        return user

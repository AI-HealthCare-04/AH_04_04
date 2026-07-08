import uuid
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

    # 소셜 로그인(google/kakao) 사용자 생성. provider만 다르고 흐름은 동일하므로 공통 메서드로 둡니다.
    async def create_social_user(self, provider: AuthProvider, social_id: str, nickname: str) -> User:
        user = User(
            provider=provider,
            social_id=social_id,
            nickname=nickname,
            onboarding_status=OnboardingStatus.PENDING,
            last_login_at=datetime.now(config.TIMEZONE),
        )
        self.session.add(user)
        await self.session.flush()
        return user

    # 게스트(체험하기) 사용자 생성.
    # 개인정보는 받지 않지만, UNIQUE(provider, social_id) 구조를 지키기 위해
    # 서버가 매 호출마다 비식별 랜덤 ID("guest:<uuid>")를 social_id로 생성합니다.
    # → 호출마다 새 게스트가 생기므로 여러 명이 동시에 체험해도 데이터가 섞이지 않습니다.
    async def create_guest_user(self, nickname: str) -> User:
        social_id = f"guest:{uuid.uuid4().hex}"
        return await self.create_social_user(AuthProvider.GUEST, social_id, nickname)

    async def update_last_login(self, user: User) -> None:
        user.last_login_at = datetime.now(config.TIMEZONE)
        await self.session.flush()

    async def update_nickname(self, user: User, nickname: str) -> User:
        user.nickname = nickname
        await self.session.flush()
        return user

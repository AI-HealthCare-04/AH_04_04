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
        # 탈퇴(soft-delete)된 사용자는 조회되지 않는다 → 인증 경로에서 자동으로 401 처리된다.
        stmt = select(User).where(User.user_id == user_id, User.deleted_at.is_(None))
        return await self.session.scalar(stmt)

    async def get_by_provider_social_id(self, provider: AuthProvider, social_id: str) -> User | None:
        stmt = select(User).where(
            User.provider == provider,
            User.social_id == social_id,
            User.deleted_at.is_(None),
        )
        return await self.session.scalar(stmt)

    async def get_deleted_by_provider_social_id(self, provider: AuthProvider, social_id: str) -> User | None:
        # 탈퇴(soft-delete)한 동일 소셜 계정 조회. 재로그인 시 신규 생성 대신 복구하기 위함.
        #   (provider, social_id) 유니크 제약이 있어, 삭제행을 두고 그대로 create하면 IntegrityError가 난다.
        stmt = select(User).where(
            User.provider == provider,
            User.social_id == social_id,
            User.deleted_at.is_not(None),
        )
        return await self.session.scalar(stmt)

    async def restore(self, user: User) -> User:
        # 탈퇴 계정 복구: deleted_at 해제 + 마지막 로그인 갱신.
        user.deleted_at = None
        user.last_login_at = datetime.now(config.TIMEZONE)
        await self.session.flush()
        return user

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

    async def soft_delete(self, user: User) -> None:
        # 물리 삭제가 아니라 deleted_at만 찍는다(soft-delete). 조회/인증에서 자동 제외된다.
        user.deleted_at = datetime.now(config.TIMEZONE)
        await self.session.flush()

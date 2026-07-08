from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.users import UserSettingsResponse, UserSettingsUpdateRequest, UserUpdateRequest
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
    async def update_settings(self, data: UserSettingsUpdateRequest) -> UserSettingsResponse:
        # 명세 §10: PATCH 응답은 '보낸 필드만'이 아니라 '전체 설정'(조회와 동일)을 반환한다.
        # 영속화(personalized_settings 연결)는 후속 백로그이므로, 지금은 기본값 위에
        # 클라이언트가 실제로 보낸 필드만 덮어써(exclude_unset) 전체 설정 형태로 돌려준다.
        return UserSettingsResponse().model_copy(update=data.model_dump(exclude_unset=True))

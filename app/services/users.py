from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.users import (
    UserSettingsResponse,
    UserSettingsUpdateRequest,
    UserUpdateRequest,
    UserWithdrawRequest,
)
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

    async def withdraw(self, user: User, data: UserWithdrawRequest) -> None:
        # soft-delete: deleted_at만 찍는다. 이후 get_user가 이 사용자를 걸러내 기존 토큰도 즉시 무효화된다.
        # 물리 파기/보존기간은 파기정책 확정 후 별도 배치로 분리한다(soft-delete 우선).
        if data.confirm is not True:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="회원탈퇴를 진행하려면 confirm이 true여야 합니다.",
            )
        await self.user_repo.soft_delete(user)
        await self.session.commit()


class UserSettingsService:
    async def update_settings(self, data: UserSettingsUpdateRequest) -> UserSettingsResponse:
        # 명세 §10: PATCH 응답은 '보낸 필드만'이 아니라 '전체 설정'(조회와 동일)을 반환한다.
        # 영속화(personalized_settings 연결)는 후속 백로그이므로, 지금은 기본값 위에
        # 클라이언트가 실제로 보낸 필드만 덮어써 전체 설정 형태로 돌려준다.
        # exclude_none: 명시적 null({"font_size": null})은 '변경 안 함'으로 무시 → 응답 필드가
        # non-null이라 null이 섞이면 응답 검증 500이 나므로 방지한다.
        return UserSettingsResponse().model_copy(update=data.model_dump(exclude_unset=True, exclude_none=True))

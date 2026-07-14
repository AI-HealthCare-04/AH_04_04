from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.users import (
    UserSettingsResponse,
    UserSettingsUpdateRequest,
    UserUpdateRequest,
    UserWithdrawRequest,
)
from app.models.settings import PersonalizedSetting
from app.models.users import User
from app.repositories.settings_repository import PersonalizedSettingRepository
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
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = PersonalizedSettingRepository(session)

    async def get_settings(self, user: User) -> UserSettingsResponse:
        # 설정을 아직 한 번도 저장한 적 없으면 기본값을 반환한다(조회는 행을 만들지 않음 — 부작용 없음).
        setting = await self.repo.get_by_user_id(user.user_id)
        if setting is None:
            return UserSettingsResponse()
        return self._to_response(setting)

    async def update_settings(self, user: User, data: UserSettingsUpdateRequest) -> UserSettingsResponse:
        # PATCH 응답은 '보낸 필드만'이 아니라 '전체 설정'(조회와 동일)을 반환한다.
        #   보낸 필드만 반영한다. 명시적 null/미전송은 '변경 안 함'(exclude_unset·exclude_none).
        #   첫 변경이면 기본값 행을 만들어 그 위에 반영한다(personalized_settings에 영속).
        setting = await self.repo.get_by_user_id(user.user_id)
        if setting is None:
            setting = await self.repo.add(PersonalizedSetting(user_id=user.user_id))
        for field, value in data.model_dump(exclude_unset=True, exclude_none=True).items():
            setattr(setting, field, value)
        await self.session.commit()
        await self.session.refresh(setting)
        return self._to_response(setting)

    @staticmethod
    def _to_response(setting: PersonalizedSetting) -> UserSettingsResponse:
        return UserSettingsResponse(
            font_size=setting.font_size,
            sound_size=setting.sound_size,
            pet_type=setting.pet_type,
            music_enabled=setting.music_enabled,
        )

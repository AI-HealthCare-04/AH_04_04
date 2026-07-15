from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.users import (
    UserInfoResponse,
    UserSettingsResponse,
    UserSettingsUpdateRequest,
    UserUpdateRequest,
    UserWithdrawRequest,
)
from app.models.enums import ActivityLevel
from app.models.settings import PersonalizedSetting
from app.models.users import User
from app.repositories.activity_profile_repository import ActivityProfileRepository
from app.repositories.dashboard_repository import DashboardRepository
from app.repositories.health_profile_repository import HealthProfileRepository
from app.repositories.settings_repository import PersonalizedSettingRepository
from app.repositories.user_repository import UserRepository


class UserManageService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.user_repo = UserRepository(session)
        # `_14 내 정보` 통합 응답을 조립하기 위한 읽기 전용 소스들(각 도메인의 단일 원천을 재사용).
        self.health_repo = HealthProfileRepository(session)
        self.activity_repo = ActivityProfileRepository(session)
        self.dashboard_repo = DashboardRepository(session)

    async def get_user_info(self, user: User) -> UserInfoResponse:
        # 화면 `_14 내 정보`용 통합 조회(GAP #5). 계정 정보 + 분산돼 있던 3개 값을 한 응답으로 모은다.
        #   - birth_date/sex : 최신 '사용자 입력' 건강프로필. 없으면(건강체크 전) null.
        #       get_latest_profile은 재평가(SERVICE_LOG)본을 제외하므로 사용자가 직접 입력한 값이 온다.
        #   - current_points : 미션 적립 합계(포인트의 단일 원천 = mission_logs). dashboard와 동일 계산.
        #   - activity_level : user_activity_profiles.current_level. 없으면 easy(홈 표시와 동일 기본값).
        profile = await self.health_repo.get_latest_profile(user.user_id)
        activity_profile = await self.activity_repo.get_by_user_id(user.user_id)
        current_points = await self.dashboard_repo.get_current_points(user.user_id)
        activity_level = activity_profile.current_level if activity_profile else ActivityLevel.EASY
        return UserInfoResponse(
            user_id=user.user_id,
            provider=user.provider,
            nickname=user.nickname,
            onboarding_status=user.onboarding_status,
            created_at=user.created_at,
            birth_date=profile.birth_date if profile else None,
            sex=profile.sex if profile else None,
            current_points=current_points,
            activity_level=activity_level,
        )

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

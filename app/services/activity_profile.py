from datetime import datetime

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import config
from app.dtos.activity_profile import ActivityProfileResponse, ActivityProfileUpdateRequest
from app.models.activity import UserActivityProfile
from app.models.enums import ActivityLevel, LevelReason
from app.models.users import User
from app.repositories.activity_profile_repository import ActivityProfileRepository


class ActivityProfileService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = ActivityProfileRepository(session)

    async def get_profile(self, user: User) -> ActivityProfileResponse:
        profile = await self.repo.get_by_user_id(user.user_id)
        if profile is None:
            profile = await self._create_default_profile(user.user_id)
        return ActivityProfileResponse.model_validate(profile)

    async def update_profile(self, user: User, data: ActivityProfileUpdateRequest) -> ActivityProfileResponse:
        if not data.accepted_by_user:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Activity level change must be accepted by user.",
            )

        profile = await self.repo.get_by_user_id(user.user_id)
        if profile is None:
            profile = self._build_default_profile(user.user_id)
            await self.repo.create_profile(profile)

        profile.current_level = data.to_level
        # 사용자 수락 변경의 결과 상태 사유는 항상 user_selected. 요청 reason_type(변경 사유)은
        # 이력용이라 여기서 저장하지 않는다(후속 activity_level_change_logs에 기록 예정).
        profile.level_reason = LevelReason.USER_SELECTED
        await self.repo.update_profile(profile)
        await self.session.commit()
        await self.session.refresh(profile)
        return ActivityProfileResponse.model_validate(profile)

    async def _create_default_profile(self, user_id: int) -> UserActivityProfile:
        profile = self._build_default_profile(user_id)
        await self.repo.create_profile(profile)
        await self.session.commit()
        await self.session.refresh(profile)
        return profile

    @staticmethod
    def _build_default_profile(user_id: int) -> UserActivityProfile:
        return UserActivityProfile(
            user_id=user_id,
            current_level=ActivityLevel.EASY,
            # 건강체크를 건너뛴 사용자의 기본 난이도도 서버 규칙에 따른 결정이므로 rule.
            level_reason=LevelReason.RULE,
            physical_assessment_id=None,
            started_at=datetime.now(config.TIMEZONE),
        )

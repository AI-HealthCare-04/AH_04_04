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
        profile.level_reason = data.reason_type
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
            level_reason=LevelReason.DEFAULT,
            physical_assessment_id=None,
            started_at=datetime.now(config.TIMEZONE),
        )

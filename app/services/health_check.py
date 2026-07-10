from datetime import datetime

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import config
from app.dtos.activity_profile import ActivityProfileResponse
from app.dtos.health_check import (
    HealthCheckSessionCreateRequest,
    HealthCheckSessionResponse,
    HealthCheckSkipResponse,
    HealthCheckVoiceRequest,
)
from app.models.activity import UserActivityProfile
from app.models.enums import (
    ActivityLevel,
    HealthCheckStatus,
    InputMethod,
    LevelReason,
    OnboardingStatus,
)
from app.models.health import HealthCheckSession
from app.models.users import User
from app.repositories.activity_profile_repository import ActivityProfileRepository
from app.repositories.health_check_repository import HealthCheckRepository


class HealthCheckService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = HealthCheckRepository(session)
        self.activity_repo = ActivityProfileRepository(session)

    async def start_session(self, user: User, data: HealthCheckSessionCreateRequest) -> HealthCheckSessionResponse:
        health_check_session = HealthCheckSession(
            user_id=user.user_id,
            status=HealthCheckStatus.STARTED,
            input_method=data.input_method,
            raw_transcript=None,
            has_estimated_value=False,
        )
        await self.repo.create_session(health_check_session)
        await self.session.commit()
        await self.session.refresh(health_check_session)
        return HealthCheckSessionResponse.model_validate(health_check_session)

    async def save_voice_transcript(
        self,
        user: User,
        session_id: int,
        data: HealthCheckVoiceRequest,
    ) -> HealthCheckSessionResponse:
        health_check_session = await self._get_started_session(session_id, user.user_id)
        health_check_session.input_method = InputMethod.VOICE
        health_check_session.raw_transcript = data.raw_transcript
        health_check_session.has_estimated_value = data.has_estimated_value
        health_check_session.status = HealthCheckStatus.COMPLETED
        health_check_session.completed_at = datetime.now(config.TIMEZONE)
        await self.repo.update_session(health_check_session)
        await self.session.commit()
        await self.session.refresh(health_check_session)
        return HealthCheckSessionResponse.model_validate(health_check_session)

    async def skip_session(self, user: User, session_id: int) -> HealthCheckSkipResponse:
        health_check_session = await self._get_started_session(session_id, user.user_id)
        health_check_session.status = HealthCheckStatus.SKIPPED
        health_check_session.completed_at = datetime.now(config.TIMEZONE)
        await self.repo.update_session(health_check_session)
        activity_profile = await self._get_or_create_default_activity_profile(user.user_id)
        user.onboarding_status = OnboardingStatus.COMPLETED
        await self.session.commit()
        await self.session.refresh(health_check_session)
        await self.session.refresh(activity_profile)
        return HealthCheckSkipResponse(
            session_id=health_check_session.session_id,
            status=health_check_session.status,
            onboarding_status=user.onboarding_status.value,
            activity_profile=ActivityProfileResponse.model_validate(activity_profile),
        )

    async def _get_started_session(self, session_id: int, user_id: int) -> HealthCheckSession:
        health_check_session = await self.repo.get_session(session_id, user_id)
        if health_check_session is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="세션을 찾을 수 없습니다.")
        if health_check_session.status != HealthCheckStatus.STARTED:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="이미 종료된 세션입니다.")
        return health_check_session

    async def _get_or_create_default_activity_profile(self, user_id: int) -> UserActivityProfile:
        activity_profile = await self.activity_repo.get_by_user_id(user_id)
        if activity_profile is not None:
            return activity_profile

        activity_profile = UserActivityProfile(
            user_id=user_id,
            current_level=ActivityLevel.EASY,
            level_reason=LevelReason.RULE,
            physical_assessment_id=None,
            started_at=datetime.now(config.TIMEZONE),
        )
        await self.activity_repo.create_profile(activity_profile)
        return activity_profile

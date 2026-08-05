from datetime import datetime

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import config
from app.dtos.health_check import (
    HealthCheckSessionCreateRequest,
    HealthCheckSessionResponse,
    HealthCheckSkipResponse,
)
from app.models.enums import (
    HealthCheckStatus,
    OnboardingStatus,
)
from app.models.health import HealthCheckSession
from app.models.users import User
from app.repositories.health_check_repository import HealthCheckRepository


class HealthCheckService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.repo = HealthCheckRepository(session)

    async def start_session(self, user: User, data: HealthCheckSessionCreateRequest) -> HealthCheckSessionResponse:
        # 중단 후 재진입(#180): 남아 있는 STARTED 세션이 있으면 새로 만들지 않고 재사용한다.
        #   → 고아 STARTED 누적을 막고 "하던 데서 이어하기"가 된다. 없을 때만 새로 생성.
        existing = await self.repo.get_latest_started(user.user_id)
        if existing is not None:
            return HealthCheckSessionResponse.model_validate(existing)
        health_check_session = HealthCheckSession(
            user_id=user.user_id,
            status=HealthCheckStatus.STARTED,
            input_method=data.input_method,
            has_estimated_value=False,
        )
        await self.repo.create_session(health_check_session)
        await self.session.commit()
        await self.session.refresh(health_check_session)
        return HealthCheckSessionResponse.model_validate(health_check_session)

    async def skip_session(self, user: User, session_id: int) -> HealthCheckSkipResponse:
        health_check_session = await self._get_started_session(session_id, user.user_id)
        health_check_session.status = HealthCheckStatus.SKIPPED
        health_check_session.completed_at = datetime.now(config.TIMEZONE)
        await self.repo.update_session(health_check_session)
        user.onboarding_status = OnboardingStatus.COMPLETED
        await self.session.commit()
        await self.session.refresh(health_check_session)
        return HealthCheckSkipResponse(
            session_id=health_check_session.session_id,
            status=health_check_session.status,
            onboarding_status=user.onboarding_status.value,
        )

    async def _get_started_session(self, session_id: int, user_id: int) -> HealthCheckSession:
        # 건너뛰기는 SKIPPED 로 전이하므로 세션 행을 잠가 동시 제출과 직렬화한다(#207 리뷰).
        health_check_session = await self.repo.get_session(session_id, user_id, for_update=True)
        if health_check_session is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="세션을 찾을 수 없습니다.")
        if health_check_session.status != HealthCheckStatus.STARTED:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="이미 종료된 세션입니다.")
        return health_check_session

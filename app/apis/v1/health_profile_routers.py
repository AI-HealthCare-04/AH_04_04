from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.health_profile import (
    HealthProfileCreateRequest,
    HealthProfileCreateResponse,
    HealthProfilePatchRequest,
    HealthProfileResponse,
)
from app.models.users import User
from app.services.health_profile import HealthProfileService

health_profile_router = APIRouter(prefix="/health-profiles", tags=["health-profiles"])


@health_profile_router.get("/me/latest", response_model=HealthProfileResponse, status_code=status.HTTP_200_OK)
async def get_latest_health_profile(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> HealthProfileResponse:
    return await HealthProfileService(session).get_latest_profile(user)


@health_profile_router.post("", response_model=HealthProfileCreateResponse, status_code=status.HTTP_201_CREATED)
async def create_health_profile(
    data: HealthProfileCreateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> HealthProfileCreateResponse:
    return await HealthProfileService(session).create_profile(user, data)


@health_profile_router.patch("/me", response_model=HealthProfileResponse, status_code=status.HTTP_200_OK)
async def update_health_profile(
    data: HealthProfilePatchRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> HealthProfileResponse:
    # 설정 '내 정보' 편집(#기록탭 §2): 키/몸무게/허리/신장상태만 덮어 새 프로필 스냅샷 생성 → 다음 추론부터 반영.
    return await HealthProfileService(session).update_profile(user, data)

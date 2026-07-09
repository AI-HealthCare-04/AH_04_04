from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.health_profile import HealthProfileCreateRequest, HealthProfileResponse
from app.models.users import User
from app.services.health_profile import HealthProfileService

health_profile_router = APIRouter(prefix="/health-profiles", tags=["health-profiles"])


@health_profile_router.get("/me/latest", response_model=HealthProfileResponse, status_code=status.HTTP_200_OK)
async def get_latest_health_profile(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> HealthProfileResponse:
    return await HealthProfileService(session).get_latest_profile(user)


@health_profile_router.post("", response_model=HealthProfileResponse, status_code=status.HTTP_201_CREATED)
async def create_health_profile(
    data: HealthProfileCreateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> HealthProfileResponse:
    return await HealthProfileService(session).create_profile(user, data)

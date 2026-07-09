from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.activity_profile import ActivityProfileResponse, ActivityProfileUpdateRequest
from app.models.users import User
from app.services.activity_profile import ActivityProfileService

activity_profile_router = APIRouter(prefix="/users/me/activity-profile", tags=["activity-profile"])


@activity_profile_router.get("", response_model=ActivityProfileResponse, status_code=status.HTTP_200_OK)
async def get_activity_profile(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> ActivityProfileResponse:
    return await ActivityProfileService(session).get_profile(user)


@activity_profile_router.patch("", response_model=ActivityProfileResponse, status_code=status.HTTP_200_OK)
async def update_activity_profile(
    data: ActivityProfileUpdateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> ActivityProfileResponse:
    return await ActivityProfileService(session).update_profile(user, data)

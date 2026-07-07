from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.users import UserInfoResponse, UserSettingsResponse, UserSettingsUpdateRequest, UserUpdateRequest
from app.models.users import User
from app.services.users import UserManageService, UserSettingsService

user_router = APIRouter(prefix="/users", tags=["users"])


@user_router.get("/me", response_model=UserInfoResponse, status_code=status.HTTP_200_OK)
async def user_me_info(
    user: Annotated[User, Depends(get_request_user)],
) -> UserInfoResponse:
    return UserInfoResponse.model_validate(user)


@user_router.patch("/me", response_model=UserInfoResponse, status_code=status.HTTP_200_OK)
async def update_user_me_info(
    update_data: UserUpdateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> UserInfoResponse:
    updated_user = await UserManageService(session).update_user(user=user, data=update_data)
    return UserInfoResponse.model_validate(updated_user)


@user_router.get("/me/settings", response_model=UserSettingsResponse, status_code=status.HTTP_200_OK)
async def get_user_settings(
    user: Annotated[User, Depends(get_request_user)],
) -> UserSettingsResponse:
    return UserSettingsResponse()


@user_router.patch("/me/settings", response_model=UserSettingsUpdateRequest, status_code=status.HTTP_200_OK)
async def update_user_settings(
    update_data: UserSettingsUpdateRequest,
    user: Annotated[User, Depends(get_request_user)],
) -> UserSettingsUpdateRequest:
    return await UserSettingsService().update_settings(update_data)

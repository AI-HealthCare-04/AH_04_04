from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.users import (
    UserInfoResponse,
    UserSettingsResponse,
    UserSettingsUpdateRequest,
    UserUpdateRequest,
    UserWithdrawRequest,
)
from app.models.users import User
from app.services.users import UserManageService, UserSettingsService

user_router = APIRouter(prefix="/users", tags=["users"])


@user_router.get("/me", response_model=UserInfoResponse, status_code=status.HTTP_200_OK)
async def user_me_info(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> UserInfoResponse:
    # `_14 내 정보`용 통합 응답(계정 정보 + birth_date·성별·보유포인트·운동강도). GAP #5.
    return await UserManageService(session).get_user_info(user)


@user_router.patch("/me", response_model=UserInfoResponse, status_code=status.HTTP_200_OK)
async def update_user_me_info(
    update_data: UserUpdateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> UserInfoResponse:
    # 닉네임 등 변경 후, GET과 동일한 통합 응답 형태로 최신 내 정보를 돌려준다.
    service = UserManageService(session)
    await service.update_user(user=user, data=update_data)
    return await service.get_user_info(user)


@user_router.delete("/me", status_code=status.HTTP_204_NO_CONTENT)
async def withdraw_user_me(
    delete_data: UserWithdrawRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> None:
    # 회원탈퇴(soft-delete). deleted_at을 찍어 이후 인증을 무효화한다.
    await UserManageService(session).withdraw(user=user, data=delete_data)


@user_router.get("/me/settings", response_model=UserSettingsResponse, status_code=status.HTTP_200_OK)
async def get_user_settings(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> UserSettingsResponse:
    return await UserSettingsService(session).get_settings(user)


@user_router.patch("/me/settings", response_model=UserSettingsResponse, status_code=status.HTTP_200_OK)
async def update_user_settings(
    update_data: UserSettingsUpdateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> UserSettingsResponse:
    return await UserSettingsService(session).update_settings(user, update_data)

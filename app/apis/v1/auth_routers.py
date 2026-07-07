from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dtos.auth import GoogleLoginRequest, LoginResponse, LogoutResponse
from app.services.auth import AuthService

auth_router = APIRouter(prefix="/auth", tags=["auth"])


@auth_router.post("/login/google", response_model=LoginResponse, status_code=status.HTTP_200_OK)
async def login_google(
    request: GoogleLoginRequest,
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> LoginResponse:
    user, access_token = await AuthService(session).login_with_google(request)
    return LoginResponse(access_token=access_token, onboarding_status=user.onboarding_status.value)


@auth_router.post("/logout", response_model=LogoutResponse, status_code=status.HTTP_200_OK)
async def logout() -> LogoutResponse:
    return LogoutResponse()

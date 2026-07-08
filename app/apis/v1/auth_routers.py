# =====================================================================================
# 인증(Auth) 라우터 — 로그인 4종. (API 명세 v7.1)
#   POST /auth/login/google  구글 로그인/회원가입 (인증 불필요)
#   POST /auth/login/kakao   카카오 로그인/회원가입 (인증 불필요, 구현 후순위)
#   POST /auth/guest         체험하기(게스트) 로그인 (인증 불필요, 매 호출 새 게스트)
#   POST /auth/logout        로그아웃 (인증 필요, 204 No Content)
# =====================================================================================

from typing import Annotated, Any

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.auth import AuthUser, GuestAuthUser, GuestLoginResponse, LoginResponse, SocialLoginRequest
from app.models.users import User
from app.services.auth import AuthService, LoginResult

auth_router = APIRouter(prefix="/auth", tags=["auth"])


# LoginResult(User + 토큰 + 신규여부) → google/kakao 응답 DTO로 변환하는 헬퍼
def _to_login_response(result: LoginResult) -> LoginResponse:
    return LoginResponse(
        user=AuthUser(
            user_id=result.user.user_id,
            nickname=result.user.nickname,
            onboarding_status=result.user.onboarding_status,
        ),
        access_token=result.access_token,
        is_new_user=result.is_new_user,
    )


# 현재 google/kakao는 실제 OAuth 미구현이라 항상 501을 반환합니다.
# OpenAPI 문서(/api/docs)에서 오해가 없도록 501 응답을 명시합니다.
# (크리덴셜 준비 후 실제 OAuth가 붙으면 200 + LoginResponse가 정상 동작합니다.)
_NOT_IMPLEMENTED_DOC: dict[int | str, dict[str, Any]] = {
    status.HTTP_501_NOT_IMPLEMENTED: {"description": "실제 OAuth 미구현 상태 — 현재는 501 반환(체험하기 이용). 크리덴셜 준비 후 구현 예정."}
}


@auth_router.post(
    "/login/google",
    response_model=LoginResponse,
    status_code=status.HTTP_200_OK,
    responses=_NOT_IMPLEMENTED_DOC,
)
async def login_google(
    request: SocialLoginRequest,
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> LoginResponse:
    result = await AuthService(session).login_with_google(request)
    return _to_login_response(result)


@auth_router.post(
    "/login/kakao",
    response_model=LoginResponse,
    status_code=status.HTTP_200_OK,
    responses=_NOT_IMPLEMENTED_DOC,
)
async def login_kakao(
    request: SocialLoginRequest,
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> LoginResponse:
    result = await AuthService(session).login_with_kakao(request)
    return _to_login_response(result)


@auth_router.post("/guest", response_model=GuestLoginResponse, status_code=status.HTTP_200_OK)
async def guest_login(
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> GuestLoginResponse:
    # 요청 본문 없음(개인정보 미수집). 매 호출마다 새 게스트가 생성됩니다.
    result = await AuthService(session).guest_login()
    return GuestLoginResponse(
        user=GuestAuthUser(
            user_id=result.user.user_id,
            nickname=result.user.nickname,
            onboarding_status=result.user.onboarding_status,
        ),
        access_token=result.access_token,
        is_new_user=result.is_new_user,
    )


@auth_router.post("/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(
    user: Annotated[User, Depends(get_request_user)],
) -> None:
    # 스테이트리스 JWT(리프레시/블록리스트 없음): 서버는 인증만 확인하고 응답 본문 없이 204를 반환합니다.
    # 실제 토큰 폐기는 클라이언트가 저장소에서 access_token을 지우는 것으로 처리합니다.
    return None

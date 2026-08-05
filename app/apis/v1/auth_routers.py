# =====================================================================================
# 인증(Auth) 라우터 — 로그인 3종. (API 명세 v1.3)
#   POST /auth/login/google  구글 로그인/회원가입 (인증 불필요)
#   POST /auth/login/kakao   카카오 로그인/회원가입 (인증 불필요)
#   POST /auth/guest         체험하기(게스트) 로그인 (인증 불필요, 매 호출 새 게스트)
#   로그아웃 라우트 없음 — 무상태 JWT라 앱이 기기에서 토큰을 파기하는 것으로 처리(명세 v1.3 공통 규칙).
# =====================================================================================

from typing import Annotated, Any

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dtos.auth import AuthUser, GuestAuthUser, GuestLoginResponse, LoginResponse, SocialLoginRequest
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


_OAUTH_ERROR_DOC: dict[int | str, dict[str, Any]] = {
    status.HTTP_401_UNAUTHORIZED: {"description": "서명·audience·issuer·만료·nonce 검증 실패"},
    status.HTTP_503_SERVICE_UNAVAILABLE: {"description": "공급자 설정 누락 또는 공개키 서버 연결 실패"},
}


@auth_router.post(
    "/login/google",
    response_model=LoginResponse,
    status_code=status.HTTP_200_OK,
    responses=_OAUTH_ERROR_DOC,
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
    responses=_OAUTH_ERROR_DOC,
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



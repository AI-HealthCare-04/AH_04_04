# =====================================================================================
# 인증(Auth) 서비스 — 로그인 비즈니스 로직. (API 명세 v7.1)
# MVP 현재 범위:
#   - guest(체험하기): 완전 동작 — 매 호출 새 익명 사용자 + JWT 발급
#   - google / kakao : 실제 OAuth 검증 미구현 → 501(미구현) 응답 (아래 사유)
#     · OAuth 클라이언트 크리덴셜(client_id/secret 등)이 아직 설정되지 않음
#     · 검증 없이 인가코드를 그대로 쓰면 아무 문자열로도 토큰이 발급되어 위험하고,
#       일회성 코드라 같은 사용자도 매번 신규 생성되는 문제가 있어 임시 발급을 막음
#   실제 OAuth는 크리덴셜 준비 후 별도 PR에서 구현 (google 먼저, kakao 후순위).
# 초기 onboarding_status = pending, 응답에 is_new_user 포함.
# =====================================================================================

from dataclasses import dataclass

import httpx
from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core import config
from app.core.utils.nickname import generate_nickname
from app.dtos.auth import SocialLoginRequest
from app.models.enums import AuthProvider
from app.models.users import User
from app.repositories.user_repository import UserRepository
from app.services.jwt import JwtService
from app.services.oauth import OAuthProfile, fetch_google_profile, fetch_kakao_profile

_HTTP_TIMEOUT = 10.0


# 로그인 처리 결과 묶음. 라우터가 이 값으로 응답 DTO를 만듭니다.
@dataclass(frozen=True)
class LoginResult:
    user: User
    access_token: str
    is_new_user: bool  # 신규 가입이면 True, 기존 사용자 재로그인이면 False


class AuthService:
    def __init__(self, session: AsyncSession):
        self.session = session
        self.user_repo = UserRepository(session)
        self.jwt_service = JwtService()

    async def login_with_google(self, data: SocialLoginRequest) -> LoginResult:
        # 크리덴셜 미구성이면 501(안전장치). 구성되면 인가코드→프로필 교환 후 (provider, social_id) 로그인.
        if not (config.GOOGLE_CLIENT_ID and config.GOOGLE_CLIENT_SECRET and config.GOOGLE_REDIRECT_URI):
            raise HTTPException(
                status_code=status.HTTP_501_NOT_IMPLEMENTED,
                detail="구글 로그인은 아직 준비 중입니다. 체험하기(게스트)로 이용해 주세요.",
            )
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT) as client:
            profile = await fetch_google_profile(data.authorization_code, client)
        return await self._social_login(AuthProvider.GOOGLE, profile)

    async def login_with_kakao(self, data: SocialLoginRequest) -> LoginResult:
        if not (config.KAKAO_CLIENT_ID and config.KAKAO_REDIRECT_URI):
            raise HTTPException(
                status_code=status.HTTP_501_NOT_IMPLEMENTED,
                detail="카카오 로그인은 아직 준비 중입니다. 체험하기(게스트)로 이용해 주세요.",
            )
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT) as client:
            profile = await fetch_kakao_profile(data.authorization_code, client)
        return await self._social_login(AuthProvider.KAKAO, profile)

    async def _social_login(self, provider: AuthProvider, profile: OAuthProfile) -> LoginResult:
        # (provider, social_id)로 기존 사용자를 찾고, 없으면 생성한다(신규=is_new_user True).
        #   탈퇴자는 get_by_provider_social_id가 deleted_at 필터로 제외 → 재로그인 시 신규 생성.
        user = await self.user_repo.get_by_provider_social_id(provider, profile.social_id)
        is_new_user = user is None
        if user is None:
            user = await self.user_repo.create_social_user(
                provider, profile.social_id, profile.nickname or generate_nickname()
            )
        else:
            await self.user_repo.update_last_login(user)
        await self.session.commit()
        await self.session.refresh(user)
        access_token = str(self.jwt_service.create_access_token(user))
        return LoginResult(user=user, access_token=access_token, is_new_user=is_new_user)

    # 게스트(체험하기): 매 호출마다 새 익명 사용자 생성. 항상 신규(is_new_user=True).
    async def guest_login(self) -> LoginResult:
        user = await self.user_repo.create_guest_user(nickname=generate_nickname())
        await self.session.commit()
        await self.session.refresh(user)
        access_token = str(self.jwt_service.create_access_token(user))
        return LoginResult(user=user, access_token=access_token, is_new_user=True)

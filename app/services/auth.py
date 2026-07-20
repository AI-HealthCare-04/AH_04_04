# =====================================================================================
# 인증(Auth) 서비스 — 로그인 비즈니스 로직. (API 명세 v7.1)
# MVP 현재 범위:
#   - guest(체험하기): 완전 동작 — 매 호출 새 익명 사용자 + JWT 발급
#   - google / kakao : Android SDK의 OIDC ID token을 서버에서 검증
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
from app.repositories.oauth_nonce_repository import OAuthNonceRepository
from app.repositories.user_repository import UserRepository
from app.services.jwt import JwtService
from app.services.oauth import OAuthProfile, verify_google_id_token, verify_kakao_id_token

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
        self.oauth_nonce_repo = OAuthNonceRepository(session)
        self.jwt_service = JwtService()

    async def login_with_google(self, data: SocialLoginRequest) -> LoginResult:
        if not config.GOOGLE_CLIENT_ID:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="구글 로그인이 설정되지 않았습니다.",
            )
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT) as client:
            profile = await verify_google_id_token(data.id_token, data.nonce, client)
        await self.oauth_nonce_repo.consume(AuthProvider.GOOGLE, data.nonce)
        return await self._social_login(AuthProvider.GOOGLE, profile)

    async def login_with_kakao(self, data: SocialLoginRequest) -> LoginResult:
        if not config.KAKAO_NATIVE_APP_KEY:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="카카오 로그인이 설정되지 않았습니다.",
            )
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT) as client:
            profile = await verify_kakao_id_token(data.id_token, data.nonce, client)
        await self.oauth_nonce_repo.consume(AuthProvider.KAKAO, data.nonce)
        return await self._social_login(AuthProvider.KAKAO, profile)

    async def _social_login(self, provider: AuthProvider, profile: OAuthProfile) -> LoginResult:
        # (provider, social_id)로 기존 사용자를 찾고, 없으면 생성한다(신규=is_new_user True).
        user = await self.user_repo.get_by_provider_social_id(provider, profile.social_id)
        if user is not None:
            await self.user_repo.update_last_login(user)
            is_new_user = False
        else:
            # 탈퇴(soft-delete)한 동일 소셜 계정이 있으면 신규 생성 대신 복구한다.
            #   (provider, social_id) 유니크 제약 때문에 그냥 create하면 IntegrityError(500)가 난다.
            deleted = await self.user_repo.get_deleted_by_provider_social_id(provider, profile.social_id)
            if deleted is not None:
                user = await self.user_repo.restore(deleted)
                is_new_user = False
            else:
                user = await self.user_repo.create_social_user(
                    provider, profile.social_id, profile.nickname or generate_nickname()
                )
                is_new_user = True
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

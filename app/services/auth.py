# =====================================================================================
# 인증(Auth) 서비스 — 로그인 비즈니스 로직. (API 명세 v7.1)
# 로그인 4종: google / kakao / guest / logout(라우터에서 처리)
# 초기 onboarding_status = pending, 응답에 is_new_user 포함.
# =====================================================================================

from dataclasses import dataclass

from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.auth import SocialLoginRequest
from app.models.users import AuthProvider, User
from app.repositories.user_repository import UserRepository
from app.services.jwt import JwtService


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

    # 소셜 로그인 공통 처리: (provider, social_id)로 조회 → 없으면 생성(신규), 있으면 로그인시각 갱신.
    async def _social_login(self, provider: AuthProvider, social_id: str, nickname: str) -> LoginResult:
        user = await self.user_repo.get_by_provider_social_id(provider, social_id)
        is_new_user = user is None
        if user is None:
            user = await self.user_repo.create_social_user(provider, social_id, nickname)
        else:
            await self.user_repo.update_last_login(user)

        await self.session.commit()
        await self.session.refresh(user)
        access_token = str(self.jwt_service.create_access_token(user))
        return LoginResult(user=user, access_token=access_token, is_new_user=is_new_user)

    async def login_with_google(self, data: SocialLoginRequest) -> LoginResult:
        # TODO(OAuth): 실제 구글 토큰 교환(인가코드→구글 토큰→sub 추출) 미구현.
        # 지금은 인가코드를 그대로 social_id로 사용하는 임시 처리. 시크릿/리다이렉트 설정 후 교체.
        social_id = data.authorization_code
        return await self._social_login(AuthProvider.GOOGLE, social_id, nickname="회원님")

    async def login_with_kakao(self, data: SocialLoginRequest) -> LoginResult:
        # TODO(OAuth): 카카오 토큰 교환 미구현(구현 후순위). 임시로 인가코드를 social_id로 사용.
        social_id = data.authorization_code
        return await self._social_login(AuthProvider.KAKAO, social_id, nickname="회원님")

    # 게스트(체험하기): 매 호출마다 새 익명 사용자 생성. 항상 신규(is_new_user=True).
    async def guest_login(self) -> LoginResult:
        user = await self.user_repo.create_guest_user(nickname="체험하는분")
        await self.session.commit()
        await self.session.refresh(user)
        access_token = str(self.jwt_service.create_access_token(user))
        return LoginResult(user=user, access_token=access_token, is_new_user=True)

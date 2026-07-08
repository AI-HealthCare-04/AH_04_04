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

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.auth import SocialLoginRequest
from app.models.users import User
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

    async def login_with_google(self, data: SocialLoginRequest) -> LoginResult:
        # TODO(OAuth): 실제 구글 OAuth 구현 시
        #   1) 인가코드로 구글 토큰 교환 → 2) id_token 검증 후 sub(구글 고유 ID) 추출
        #   3) sub를 social_id로 (provider, social_id) 조회/생성 후 JWT 발급
        # 크리덴셜 준비 전까지는 미구현 응답으로 둡니다(임의 문자열 토큰 발급 방지).
        raise HTTPException(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            detail="구글 로그인은 아직 준비 중입니다. 체험하기(게스트)로 이용해 주세요.",
        )

    async def login_with_kakao(self, data: SocialLoginRequest) -> LoginResult:
        # TODO(OAuth): 카카오 로그인은 MVP 후순위. 구글 방식과 동일하게 구현 예정.
        raise HTTPException(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            detail="카카오 로그인은 아직 준비 중입니다. 체험하기(게스트)로 이용해 주세요.",
        )

    # 게스트(체험하기): 매 호출마다 새 익명 사용자 생성. 항상 신규(is_new_user=True).
    async def guest_login(self) -> LoginResult:
        user = await self.user_repo.create_guest_user(nickname="체험하는분")
        await self.session.commit()
        await self.session.refresh(user)
        access_token = str(self.jwt_service.create_access_token(user))
        return LoginResult(user=user, access_token=access_token, is_new_user=True)

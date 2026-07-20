# =====================================================================================
# 인증(Auth) 도메인 DTO — 로그인 요청/응답의 형태를 정의합니다. (API 명세 v7.1 기준)
# 성공 응답은 별도 래퍼 없이 평면 구조, 실패는 전역 핸들러가 {"error_detail": ...}로 통일.
# =====================================================================================

from pydantic import BaseModel, Field


# [요청] 소셜 로그인 공통 요청. Android SDK의 ID token과 발급 요청에 사용한 nonce를 함께 보냅니다.
class SocialLoginRequest(BaseModel):
    id_token: str = Field(..., min_length=1, max_length=10000, description="공급자 OIDC ID token")
    nonce: str = Field(..., min_length=16, max_length=512, description="ID token 발급 요청에 사용한 nonce")


# [응답] 로그인 응답에 담기는 사용자 요약 정보 (google/kakao 공통)
class AuthUser(BaseModel):
    user_id: int
    nickname: str
    onboarding_status: str  # 로그인 직후 신규 사용자는 "pending"


# [응답] 게스트 전용 사용자 요약. 위 AuthUser에 is_guest 플래그만 추가됨.
class GuestAuthUser(AuthUser):
    is_guest: bool = True


# [응답] google/kakao 로그인 응답. 예:
# { "user": {user_id,nickname,onboarding_status}, "access_token", "token_type":"bearer", "is_new_user" }
class LoginResponse(BaseModel):
    user: AuthUser
    access_token: str
    token_type: str = "bearer"
    is_new_user: bool


# [응답] 게스트 로그인 응답. user에 is_guest:true 가 포함된다는 점만 다름.
class GuestLoginResponse(BaseModel):
    user: GuestAuthUser
    access_token: str
    token_type: str = "bearer"
    is_new_user: bool

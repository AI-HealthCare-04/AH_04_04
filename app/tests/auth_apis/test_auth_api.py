# =====================================================================================
# 인증(Auth) API 테스트. (API 명세 v7.1)
# DB가 필요한 해피패스(실제 로그인 → 유저 생성)는 테스트 DB 픽스처 준비 후 추가(아래 TODO).
# 여기서는 DB 없이 검증 가능한 것만 다룹니다: logout 인증 가드 + 로그인 요청 검증.
# =====================================================================================

from httpx import AsyncClient
from starlette import status


# 로그아웃은 인증 필요(Bearer). 토큰 없이 부르면 401.
async def test_logout_requires_auth(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/logout")
    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    assert "error_detail" in response.json()  # 전역 핸들러가 명세 규격으로 통일


# google 로그인은 authorization_code 필수 → 없으면 422 (검증이 DB 접근 전에 막음).
async def test_google_login_requires_authorization_code(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/login/google", json={})
    assert response.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT


# kakao 로그인도 동일.
async def test_kakao_login_requires_authorization_code(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/login/kakao", json={})
    assert response.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT


# TODO(test-db-fixture): DB 세션 픽스처 준비 후 해피패스 통합 테스트 추가
#   - POST /auth/guest: 매 호출마다 다른 user_id + 다른 토큰, is_new_user=true, user.is_guest=true,
#                       onboarding_status="pending" (호출 2번 → user_id 서로 다른지)
#   - POST /auth/login/google (신규): is_new_user=true / (재로그인): is_new_user=false
#   - 발급된 access_token으로 GET /users/me 접근되는지 (end-to-end)

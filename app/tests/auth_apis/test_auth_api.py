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
    body = response.json()
    # 검증 실패도 전역 핸들러가 명세 규격 {"error_detail": ...}로 통일한다(기본 {"detail":[...]} 아님).
    assert "error_detail" in body
    assert "detail" not in body


# kakao 로그인도 동일.
async def test_kakao_login_requires_authorization_code(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/login/kakao", json={})
    assert response.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT


# google/kakao는 실제 OAuth 미구현이라, 유효한 코드를 보내도 501(미구현)을 반환해야 합니다.
# (임의 문자열로 토큰이 발급되던 문제 방지 — 지영님 리뷰 반영)
async def test_google_login_not_implemented(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/login/google", json={"authorization_code": "dummy"})
    assert response.status_code == status.HTTP_501_NOT_IMPLEMENTED
    assert "error_detail" in response.json()


async def test_kakao_login_not_implemented(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/login/kakao", json={"authorization_code": "dummy"})
    assert response.status_code == status.HTTP_501_NOT_IMPLEMENTED
    assert "error_detail" in response.json()


# TODO(test-db-fixture): DB 세션 픽스처 준비 후 게스트 해피패스 통합 테스트 추가
#   - POST /auth/guest: 호출마다 다른 user_id + 다른 토큰, is_new_user=true, user.is_guest=true,
#                       onboarding_status="pending"
#   - 발급된 access_token으로 GET /users/me 접근되는지 (end-to-end)
# (google/kakao 재로그인 테스트는 실제 OAuth 구현 PR에서 함께 추가)

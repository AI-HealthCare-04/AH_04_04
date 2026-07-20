# =====================================================================================
# 인증(Auth) API 테스트. (API 명세 v7.1)
# DB가 필요한 해피패스(실제 로그인 → 유저 생성)는 테스트 DB 픽스처 준비 후 추가(아래 TODO).
# 여기서는 DB 없이 검증 가능한 것만 다룹니다: logout 인증 가드 + 로그인 요청 검증.
# =====================================================================================

import pytest
from httpx import AsyncClient
from starlette import status

from app.core import config


# 로그아웃은 인증 필요(Bearer). 토큰 없이 부르면 401.
async def test_logout_requires_auth(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/logout")
    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    assert "error_detail" in response.json()  # 전역 핸들러가 명세 규격으로 통일


# google 로그인은 id_token과 nonce 필수 → 없으면 422.
async def test_google_login_requires_id_token_and_nonce(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/login/google", json={})
    assert response.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT
    body = response.json()
    # 검증 실패도 전역 핸들러가 명세 규격 {"error_detail": ...}로 통일한다(기본 {"detail":[...]} 아님).
    assert "error_detail" in body
    assert "detail" not in body


# kakao 로그인도 동일.
async def test_kakao_login_requires_id_token_and_nonce(client: AsyncClient) -> None:
    response = await client.post("/api/v1/auth/login/kakao", json={})
    assert response.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT


# 공급자 audience가 미설정이면 외부 검증 전에 503으로 닫힌다.
async def test_google_login_unconfigured(client: AsyncClient, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config, "GOOGLE_CLIENT_ID", "")
    response = await client.post(
        "/api/v1/auth/login/google",
        json={"id_token": "dummy", "nonce": "nonce-value-long-enough"},
    )
    assert response.status_code == status.HTTP_503_SERVICE_UNAVAILABLE
    assert "error_detail" in response.json()


async def test_kakao_login_unconfigured(client: AsyncClient, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config, "KAKAO_NATIVE_APP_KEY", "")
    response = await client.post(
        "/api/v1/auth/login/kakao",
        json={"id_token": "dummy", "nonce": "nonce-value-long-enough"},
    )
    assert response.status_code == status.HTTP_503_SERVICE_UNAVAILABLE
    assert "error_detail" in response.json()


# DB 세션이 제공되는 환경에서는 test_oauth.py가 신규/재로그인과 nonce 재사용 거부를 검증한다.
# TODO(test-db-fixture): 게스트 해피패스 통합 테스트 추가
#   - POST /auth/guest: 호출마다 다른 user_id + 다른 토큰, is_new_user=true, user.is_guest=true,
#                       onboarding_status="pending"
#   - 발급된 access_token으로 GET /users/me 접근되는지 (end-to-end)

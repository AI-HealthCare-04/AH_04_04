# =====================================================================================
# OAuth 구조 테스트 (mocked). 실제 구글/카카오 호출 없이 검증한다.
#   - oauth 계층: httpx.MockTransport로 provider 응답을 흉내 → 프로필 파싱/에러 매핑 검증
#   - 로그인 흐름: 크리덴셜을 구성하고 fetch를 패치 → (provider, social_id) 신규/재로그인 검증
# 크리덴셜 미구성 상태의 501은 기존 test_auth_api.py에서 커버한다.
# =====================================================================================
import httpx
import pytest
from fastapi import HTTPException
from httpx import AsyncClient
from starlette import status

from app.core import config
from app.services import auth as auth_module
from app.services.oauth import OAuthProfile, fetch_google_profile, fetch_kakao_profile


def _mock_client(handler: object) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))  # type: ignore[arg-type]


async def test_fetch_google_profile_parses_sub_and_name() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/token"):
            return httpx.Response(200, json={"access_token": "at"})
        return httpx.Response(200, json={"sub": "google-123", "name": "홍길동"})

    async with _mock_client(handler) as client:
        profile = await fetch_google_profile("code", client)

    assert profile == OAuthProfile(social_id="google-123", nickname="홍길동")


async def test_fetch_google_profile_bad_code_maps_to_400() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(400, json={"error": "invalid_grant"})

    async with _mock_client(handler) as client:
        with pytest.raises(HTTPException) as exc:
            await fetch_google_profile("bad", client)

    assert exc.value.status_code == status.HTTP_400_BAD_REQUEST


async def test_fetch_kakao_profile_parses_id_and_nickname() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/token"):
            return httpx.Response(200, json={"access_token": "at"})
        return httpx.Response(200, json={"id": 987654, "kakao_account": {"profile": {"nickname": "카카오유저"}}})

    async with _mock_client(handler) as client:
        profile = await fetch_kakao_profile("code", client)

    assert profile == OAuthProfile(social_id="987654", nickname="카카오유저")


def _configure_google(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config, "GOOGLE_CLIENT_ID", "id")
    monkeypatch.setattr(config, "GOOGLE_CLIENT_SECRET", "secret")
    monkeypatch.setattr(config, "GOOGLE_REDIRECT_URI", "https://app/callback")


async def test_google_login_creates_then_reuses_user(
    db_client: AsyncClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    _configure_google(monkeypatch)

    async def fake_fetch(code: str, client: httpx.AsyncClient) -> OAuthProfile:
        return OAuthProfile(social_id="g-1", nickname="구글이")

    monkeypatch.setattr(auth_module, "fetch_google_profile", fake_fetch)

    # 첫 로그인 → 신규 생성
    first = await db_client.post("/api/v1/auth/login/google", json={"authorization_code": "x"})
    assert first.status_code == status.HTTP_200_OK
    assert first.json()["is_new_user"] is True
    user_id = first.json()["user"]["user_id"]

    # 같은 소셜 계정 재로그인 → 기존 사용자(is_new_user False), 동일 user_id
    second = await db_client.post("/api/v1/auth/login/google", json={"authorization_code": "y"})
    assert second.json()["is_new_user"] is False
    assert second.json()["user"]["user_id"] == user_id

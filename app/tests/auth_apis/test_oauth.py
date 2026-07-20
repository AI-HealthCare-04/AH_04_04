from datetime import UTC, datetime, timedelta

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import HTTPException
from httpx import AsyncClient
from starlette import status

from app.core import config
from app.services import auth as auth_module
from app.services.oauth import OAuthProfile, _cache_ttl, _verify_id_token


def _signed_token(
    *,
    audience: str = "client-id",
    issuer: str = "https://accounts.google.com",
    nonce: str = "nonce-value-long-enough",
    expired: bool = False,
) -> tuple[str, dict[str, object]]:
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public_jwk = jwt.algorithms.RSAAlgorithm.to_jwk(private_key.public_key(), as_dict=True)
    public_jwk.update({"kid": "test-key", "alg": "RS256", "use": "sig"})
    now = datetime.now(UTC)
    token = jwt.encode(
        {
            "iss": issuer,
            "aud": audience,
            "sub": "provider-user-1",
            "name": "테스트 사용자",
            "iat": now,
            "exp": now - timedelta(seconds=10) if expired else now + timedelta(minutes=5),
            "nonce": nonce,
        },
        private_key,
        algorithm="RS256",
        headers={"kid": "test-key"},
    )
    return token, {"keys": [public_jwk]}


def test_verify_id_token_accepts_valid_signature_and_nonce() -> None:
    token, jwks = _signed_token()
    claims = _verify_id_token(
        token,
        "nonce-value-long-enough",
        "client-id",
        frozenset({"https://accounts.google.com"}),
        jwks,
    )
    assert claims["sub"] == "provider-user-1"


@pytest.mark.parametrize(
    ("kwargs", "nonce"),
    [
        ({"audience": "other-client"}, "nonce-value-long-enough"),
        ({"issuer": "https://attacker.example"}, "nonce-value-long-enough"),
        ({"expired": True}, "nonce-value-long-enough"),
        ({}, "different-nonce-value"),
    ],
)
def test_verify_id_token_rejects_invalid_claims(kwargs: dict[str, object], nonce: str) -> None:
    token, jwks = _signed_token(**kwargs)  # type: ignore[arg-type]
    with pytest.raises(HTTPException) as exc:
        _verify_id_token(
            token,
            nonce,
            "client-id",
            frozenset({"https://accounts.google.com"}),
            jwks,
        )
    assert exc.value.status_code == status.HTTP_401_UNAUTHORIZED


def test_jwks_cache_ttl_is_bounded() -> None:
    assert _cache_ttl("public, max-age=120") == 120
    assert _cache_ttl("max-age=999999") == 24 * 3600


async def test_google_login_creates_then_reuses_user(db_client: AsyncClient, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config, "GOOGLE_CLIENT_ID", "client-id")

    async def fake_verify(token: str, nonce: str, client: httpx.AsyncClient) -> OAuthProfile:
        return OAuthProfile(social_id="g-1", nickname="구글이")

    monkeypatch.setattr(auth_module, "verify_google_id_token", fake_verify)
    first = await db_client.post(
        "/api/v1/auth/login/google",
        json={"id_token": "signed-token", "nonce": "nonce-value-long-enough-1"},
    )
    assert first.status_code == status.HTTP_200_OK
    assert first.json()["is_new_user"] is True
    user_id = first.json()["user"]["user_id"]

    second = await db_client.post(
        "/api/v1/auth/login/google",
        json={"id_token": "signed-token", "nonce": "nonce-value-long-enough-2"},
    )
    assert second.status_code == status.HTTP_200_OK
    assert second.json()["is_new_user"] is False
    assert second.json()["user"]["user_id"] == user_id


async def test_google_login_rejects_reused_nonce(db_client: AsyncClient, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config, "GOOGLE_CLIENT_ID", "client-id")

    async def fake_verify(token: str, nonce: str, client: httpx.AsyncClient) -> OAuthProfile:
        return OAuthProfile(social_id="g-replay", nickname="구글이")

    monkeypatch.setattr(auth_module, "verify_google_id_token", fake_verify)
    body = {"id_token": "signed-token", "nonce": "nonce-value-replayed"}
    first = await db_client.post("/api/v1/auth/login/google", json=body)
    second = await db_client.post("/api/v1/auth/login/google", json=body)
    assert first.status_code == status.HTTP_200_OK
    assert second.status_code == status.HTTP_401_UNAUTHORIZED

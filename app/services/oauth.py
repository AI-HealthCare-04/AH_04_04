"""Google/Kakao OIDC ID token 검증."""

import asyncio
import hmac
import re
import time
from dataclasses import dataclass

import httpx
import jwt
from fastapi import HTTPException, status

from app.core import config

_GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
_GOOGLE_ISSUERS = frozenset({"accounts.google.com", "https://accounts.google.com"})
_KAKAO_JWKS_URL = "https://kauth.kakao.com/.well-known/jwks.json"
_KAKAO_ISSUERS = frozenset({"https://kauth.kakao.com"})
_DEFAULT_JWKS_TTL_SECONDS = 3600
_MAX_JWKS_TTL_SECONDS = 24 * 3600


@dataclass(frozen=True)
class OAuthProfile:
    social_id: str
    nickname: str | None


@dataclass(frozen=True)
class _CachedJwks:
    value: dict[str, object]
    expires_at: float


class _JwksCache:
    def __init__(self) -> None:
        self._entries: dict[str, _CachedJwks] = {}
        self._lock = asyncio.Lock()

    async def get(self, url: str, client: httpx.AsyncClient) -> dict[str, object]:
        now = time.monotonic()
        cached = self._entries.get(url)
        if cached is not None and cached.expires_at > now:
            return cached.value

        async with self._lock:
            now = time.monotonic()
            cached = self._entries.get(url)
            if cached is not None and cached.expires_at > now:
                return cached.value
            try:
                response = await client.get(url)
                response.raise_for_status()
                payload = response.json()
                if not isinstance(payload, dict) or not isinstance(payload.get("keys"), list):
                    raise ValueError("invalid JWKS response")
            except (httpx.HTTPError, ValueError) as exc:
                raise HTTPException(
                    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                    detail="로그인 공급자 검증 서버에 연결할 수 없습니다.",
                ) from exc

            ttl = _cache_ttl(response.headers.get("cache-control", ""))
            self._entries[url] = _CachedJwks(payload, now + ttl)
            return payload


def _cache_ttl(cache_control: str) -> int:
    match = re.search(r"(?:^|,)\s*max-age=(\d+)", cache_control, re.IGNORECASE)
    if match is None:
        return _DEFAULT_JWKS_TTL_SECONDS
    return min(int(match.group(1)), _MAX_JWKS_TTL_SECONDS)


def _invalid_token() -> HTTPException:
    return HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="유효하지 않은 로그인 정보입니다.")


def _verify_id_token(
    token: str,
    nonce: str,
    audience: str,
    issuers: frozenset[str],
    jwks: dict[str, object],
) -> dict[str, object]:
    try:
        header = jwt.get_unverified_header(token)
        if header.get("alg") != "RS256" or not isinstance(header.get("kid"), str):
            raise _invalid_token()
        key = next(
            (
                candidate
                for candidate in jwt.PyJWKSet.from_dict(jwks).keys
                if candidate.key_id == header["kid"] and candidate.algorithm_name == "RS256"
            ),
            None,
        )
        if key is None:
            raise _invalid_token()
        claims = jwt.decode(
            token,
            key.key,
            algorithms=["RS256"],
            audience=audience,
            leeway=config.JWT_LEEWAY,
            options={
                "require": ["iss", "aud", "sub", "iat", "exp", "nonce"],
                "verify_iss": False,
            },
        )
        issuer = claims.get("iss")
        subject = claims.get("sub")
        token_nonce = claims.get("nonce")
        if issuer not in issuers or not isinstance(subject, str) or not subject.strip():
            raise _invalid_token()
        if not isinstance(token_nonce, str) or not hmac.compare_digest(token_nonce, nonce):
            raise _invalid_token()
        return claims
    except HTTPException:
        raise
    except (jwt.PyJWTError, KeyError, TypeError, ValueError) as exc:
        raise _invalid_token() from exc


_jwks_cache = _JwksCache()


async def verify_google_id_token(token: str, nonce: str, client: httpx.AsyncClient) -> OAuthProfile:
    jwks = await _jwks_cache.get(_GOOGLE_JWKS_URL, client)
    claims = _verify_id_token(token, nonce, config.GOOGLE_CLIENT_ID, _GOOGLE_ISSUERS, jwks)
    nickname = claims.get("name")
    return OAuthProfile(str(claims["sub"]), nickname if isinstance(nickname, str) else None)


async def verify_kakao_id_token(token: str, nonce: str, client: httpx.AsyncClient) -> OAuthProfile:
    jwks = await _jwks_cache.get(_KAKAO_JWKS_URL, client)
    claims = _verify_id_token(token, nonce, config.KAKAO_NATIVE_APP_KEY, _KAKAO_ISSUERS, jwks)
    nickname = claims.get("nickname")
    return OAuthProfile(str(claims["sub"]), nickname if isinstance(nickname, str) else None)

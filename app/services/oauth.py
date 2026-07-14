# =====================================================================================
# OAuth 프로필 조회 — 인가코드를 provider(google/kakao) 토큰·유저정보 엔드포인트로 교환한다.
#   1) authorization_code → access_token (token endpoint)
#   2) access_token → 유저 고유 ID(sub/id) + 닉네임 (userinfo endpoint)
# HTTP 클라이언트는 호출자가 주입한다(테스트에서 httpx.MockTransport로 대체 가능).
# 인가코드가 유효하지 않거나 응답이 예상과 다르면 400(유효하지 않은 인가 코드)로 통일한다.
# =====================================================================================
from dataclasses import dataclass

import httpx
from fastapi import HTTPException, status

from app.core import config

_GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
_GOOGLE_USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"
_KAKAO_TOKEN_URL = "https://kauth.kakao.com/oauth/token"
_KAKAO_USERINFO_URL = "https://kapi.kakao.com/v2/user/me"


@dataclass(frozen=True)
class OAuthProfile:
    social_id: str  # provider가 부여한 사용자 고유 ID (google: sub, kakao: id)
    nickname: str | None  # provider가 준 표시 이름(없을 수 있음)


def _invalid_code() -> HTTPException:
    return HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="유효하지 않은 인가 코드입니다.")


def _require_social_id(raw: object) -> str:
    # provider 고유 ID(sub/id)가 null이거나 빈 값이면 거절한다.
    #   (str(None)="None"을 social_id로 쓰면 비정상 응답들이 한 계정으로 뭉치는 위험)
    if raw is None or str(raw).strip() == "":
        raise _invalid_code()
    return str(raw)


async def fetch_google_profile(code: str, client: httpx.AsyncClient) -> OAuthProfile:
    try:
        token = await client.post(
            _GOOGLE_TOKEN_URL,
            data={
                "code": code,
                "client_id": config.GOOGLE_CLIENT_ID,
                "client_secret": config.GOOGLE_CLIENT_SECRET,
                "redirect_uri": config.GOOGLE_REDIRECT_URI,
                "grant_type": "authorization_code",
            },
        )
        token.raise_for_status()
        info = await client.get(
            _GOOGLE_USERINFO_URL,
            headers={"Authorization": f"Bearer {token.json()['access_token']}"},
        )
        info.raise_for_status()
        data = info.json()
        return OAuthProfile(social_id=_require_social_id(data.get("sub")), nickname=data.get("name"))
    except (httpx.HTTPError, KeyError, ValueError) as exc:  # 네트워크/HTTP오류·응답형식 불일치
        raise _invalid_code() from exc


async def fetch_kakao_profile(code: str, client: httpx.AsyncClient) -> OAuthProfile:
    payload = {
        "grant_type": "authorization_code",
        "client_id": config.KAKAO_CLIENT_ID,
        "redirect_uri": config.KAKAO_REDIRECT_URI,
        "code": code,
    }
    if config.KAKAO_CLIENT_SECRET:
        payload["client_secret"] = config.KAKAO_CLIENT_SECRET
    try:
        token = await client.post(_KAKAO_TOKEN_URL, data=payload)
        token.raise_for_status()
        info = await client.get(
            _KAKAO_USERINFO_URL,
            headers={"Authorization": f"Bearer {token.json()['access_token']}"},
        )
        info.raise_for_status()
        data = info.json()
        nickname = data.get("kakao_account", {}).get("profile", {}).get("nickname")
        return OAuthProfile(social_id=_require_social_id(data.get("id")), nickname=nickname)
    except (httpx.HTTPError, KeyError, ValueError) as exc:
        raise _invalid_code() from exc

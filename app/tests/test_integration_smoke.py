# =====================================================================================
# 통합 스모크 테스트 — 테스트 DB 픽스처(db_client)가 전 스택을 관통하는지 검증한다.
# 게스트 로그인으로 실제 유저를 MySQL에 만들고, 발급된 토큰으로 인증 API를 호출한다.
# MySQL이 없으면 db_client 픽스처가 스킵한다. (도메인별 상세 통합 테스트는 이 위에서 추가한다.)
# =====================================================================================
from httpx import AsyncClient
from starlette import status


async def test_guest_login_creates_user_and_authenticates(db_client: AsyncClient) -> None:
    # 1) 게스트 로그인 → 새 유저가 DB에 생성되고 토큰이 발급된다.
    login = await db_client.post("/api/v1/auth/guest")
    assert login.status_code == status.HTTP_200_OK
    body = login.json()
    assert body["is_new_user"] is True
    assert body["user"]["is_guest"] is True
    assert body["user"]["onboarding_status"] == "pending"

    token = body["access_token"]
    user_id = body["user"]["user_id"]

    # 2) 발급된 access_token으로 인증 API 호출이 된다(end-to-end).
    me = await db_client.get("/api/v1/users/me", headers={"Authorization": f"Bearer {token}"})
    assert me.status_code == status.HTTP_200_OK
    assert me.json()["user_id"] == user_id


async def test_guest_login_issues_distinct_users(db_client: AsyncClient) -> None:
    # 호출마다 새 게스트가 만들어진다(user_id·토큰이 서로 다르다).
    first = (await db_client.post("/api/v1/auth/guest")).json()
    second = (await db_client.post("/api/v1/auth/guest")).json()

    assert first["user"]["user_id"] != second["user"]["user_id"]
    assert first["access_token"] != second["access_token"]

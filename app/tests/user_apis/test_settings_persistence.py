# =====================================================================================
# 설정 영속화 통합 테스트 (실 MySQL, db_client).
# 핵심 계약: PATCH로 바꾼 설정이 personalized_settings에 저장돼, 다음 GET(별도 요청)에서
# 그대로 조회된다. (스텁이면 요청이 끝나면 값이 사라져 이 테스트가 실패한다.)
# =====================================================================================
from httpx import AsyncClient
from starlette import status


async def _guest_auth(db_client: AsyncClient) -> dict[str, str]:
    login = await db_client.post("/api/v1/auth/guest")
    return {"Authorization": f"Bearer {login.json()['access_token']}"}


async def test_settings_default_when_never_set(db_client: AsyncClient) -> None:
    # 설정을 한 번도 저장 안 한 유저는 기본값을 받는다(행 없어도 500 아님).
    auth = await _guest_auth(db_client)
    resp = await db_client.get("/api/v1/users/me/settings", headers=auth)
    assert resp.status_code == status.HTTP_200_OK
    assert resp.json() == {
        "font_size": "medium",
        "sound_size": "medium",
        "pet_type": "default",
        "music_enabled": True,
    }


async def test_settings_persist_across_requests(db_client: AsyncClient) -> None:
    auth = await _guest_auth(db_client)

    # PATCH로 일부 변경
    patched = await db_client.patch(
        "/api/v1/users/me/settings",
        json={"font_size": "large", "music_enabled": False, "pet_type": "cat"},
        headers=auth,
    )
    assert patched.status_code == status.HTTP_200_OK
    assert patched.json()["font_size"] == "large"

    # 별도 GET 요청에서 그대로 조회되면 = 영속화됨
    got = await db_client.get("/api/v1/users/me/settings", headers=auth)
    assert got.json() == {
        "font_size": "large",
        "sound_size": "medium",  # 안 보낸 필드는 기본값 유지
        "pet_type": "cat",
        "music_enabled": False,
    }


async def test_settings_partial_update_keeps_previous(db_client: AsyncClient) -> None:
    auth = await _guest_auth(db_client)

    await db_client.patch("/api/v1/users/me/settings", json={"font_size": "large"}, headers=auth)
    # 두 번째 PATCH는 sound_size만 → 앞서 저장한 font_size는 유지돼야 한다.
    await db_client.patch("/api/v1/users/me/settings", json={"sound_size": "small"}, headers=auth)

    got = await db_client.get("/api/v1/users/me/settings", headers=auth)
    assert got.json()["font_size"] == "large"
    assert got.json()["sound_size"] == "small"

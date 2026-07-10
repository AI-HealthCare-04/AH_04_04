# =====================================================================================
# 회원탈퇴(DELETE /users/me) 통합 테스트.
# soft-delete라 물리 삭제는 없지만, 핵심 계약은 "탈퇴 즉시 기존 토큰이 무효화된다"는 것.
# get_user가 deleted_at IS NULL로 필터하므로, 탈퇴 후 같은 토큰 요청은 401이 되어야 한다.
# =====================================================================================
from httpx import AsyncClient
from starlette import status


async def _guest_auth(db_client: AsyncClient) -> dict[str, str]:
    login = await db_client.post("/api/v1/auth/guest")
    return {"Authorization": f"Bearer {login.json()['access_token']}"}


async def test_withdraw_soft_deletes_and_invalidates_token(db_client: AsyncClient) -> None:
    auth = await _guest_auth(db_client)

    # 탈퇴 confirm=true → 204(No Content)
    withdrawn = await db_client.request("DELETE", "/api/v1/users/me", json={"confirm": True}, headers=auth)
    assert withdrawn.status_code == status.HTTP_204_NO_CONTENT

    # 같은 토큰으로 재요청 → deleted_at 필터로 조회 제외 → 401(탈퇴 즉시 발효)
    me = await db_client.get("/api/v1/users/me", headers=auth)
    assert me.status_code == status.HTTP_401_UNAUTHORIZED


async def test_withdraw_requires_confirm_true(db_client: AsyncClient) -> None:
    auth = await _guest_auth(db_client)

    # confirm=false면 탈퇴하지 않고 400
    rejected = await db_client.request("DELETE", "/api/v1/users/me", json={"confirm": False}, headers=auth)
    assert rejected.status_code == status.HTTP_400_BAD_REQUEST

    # 탈퇴가 안 됐으므로 토큰은 그대로 유효
    me = await db_client.get("/api/v1/users/me", headers=auth)
    assert me.status_code == status.HTTP_200_OK


async def test_withdraw_requires_authentication(db_client: AsyncClient) -> None:
    # 인증 없이 탈퇴 시도 → 401
    resp = await db_client.request("DELETE", "/api/v1/users/me", json={"confirm": True})
    assert resp.status_code == status.HTTP_401_UNAUTHORIZED


async def test_withdraw_missing_confirm_returns_422(db_client: AsyncClient) -> None:
    # 계약: confirm=false는 400(비즈니스 거부), confirm 누락은 422(스키마 검증 실패).
    # confirm이 필수 필드라 누락은 서비스에 닿기 전 Pydantic 검증에서 422가 된다(팀 에러표준 = 명세 v7.3).
    auth = await _guest_auth(db_client)
    resp = await db_client.request("DELETE", "/api/v1/users/me", json={}, headers=auth)
    assert resp.status_code == status.HTTP_422_UNPROCESSABLE_CONTENT

    # 탈퇴가 안 됐으므로 토큰은 그대로 유효
    me = await db_client.get("/api/v1/users/me", headers=auth)
    assert me.status_code == status.HTTP_200_OK

# =====================================================================================
# 설정 '내 정보' 편집(#기록탭 §2) E2E — PATCH /health-profiles/me
#
# health_profiles 는 append-only 라 편집은 in-place 가 아니라 **새 스냅샷 행 생성**이다.
#   - 보낸 필드만 최신 스냅샷에 덮고 bmi·protein_challenge_allowed 를 재계산한다.
#   - 새 profile_id 가 발급되고, GET /me/latest 는 그 새 스냅샷을 돌려준다(다음 추론부터 반영).
#   - 허리둘레는 명시적 null 로 '측정 안 함'을 지울 수 있다(미전송과 구분).
# =====================================================================================
from httpx import AsyncClient
from starlette import status

API = "/api/v1"


async def _guest(db_client: AsyncClient) -> dict[str, str]:
    login = await db_client.post(f"{API}/auth/guest")
    return {"Authorization": f"Bearer {login.json()['access_token']}"}


async def _create_profile(db_client: AsyncClient, auth: dict[str, str]) -> int:
    resp = await db_client.post(
        f"{API}/health-profiles",
        json={
            "birth_date": "1958-03-21",
            "sex": "male",
            "height_cm": 168,
            "weight_kg": 63.5,
            "waist_cm": 84,
            "walk_days": 5,
            "musc_days": 0,
            "kidney_status": "none",
            "protein_restriction_status": "none",
            "activity_input_source": "self_report",
            "input_method": "form",
            "has_estimated_value": False,
        },
        headers=auth,
    )
    assert resp.status_code == status.HTTP_201_CREATED
    return resp.json()["profile_id"]


async def test_patch_creates_new_snapshot_and_recalculates(db_client: AsyncClient) -> None:
    auth = await _guest(db_client)
    original_id = await _create_profile(db_client, auth)

    patched = await db_client.patch(
        f"{API}/health-profiles/me",
        json={"height_cm": 170, "weight_kg": 70, "waist_cm": 88, "kidney_status": "kidney_disease"},
        headers=auth,
    )
    assert patched.status_code == status.HTTP_200_OK
    body = patched.json()

    # 새 스냅샷(id 변경) + 편집분 반영 + bmi 재계산(70 / 1.7^2 = 24.2) + 신장질환→단백질 챌린지 차단
    assert body["profile_id"] != original_id
    assert body["height_cm"] == 170.0
    assert body["weight_kg"] == 70.0
    assert body["waist_cm"] == 88.0
    assert body["bmi"] == 24.2
    assert body["kidney_status"] == "kidney_disease"
    assert body["protein_challenge_allowed"] is False
    # 편집 안 한 표시 전용 필드는 보존
    assert body["sex"] == "male"
    assert body["birth_date"] == "1958-03-21"

    # GET /me/latest 는 방금 만든 새 스냅샷을 돌려준다.
    latest = await db_client.get(f"{API}/health-profiles/me/latest", headers=auth)
    assert latest.status_code == status.HTTP_200_OK
    assert latest.json()["profile_id"] == body["profile_id"]
    assert latest.json()["height_cm"] == 170.0


async def test_patch_waist_null_clears_and_omitted_fields_keep_latest(db_client: AsyncClient) -> None:
    auth = await _guest(db_client)
    await _create_profile(db_client, auth)

    # 허리둘레만 명시적 null → '측정 안 함'으로 지운다. 나머지는 최신값 유지.
    patched = await db_client.patch(f"{API}/health-profiles/me", json={"waist_cm": None}, headers=auth)
    assert patched.status_code == status.HTTP_200_OK
    body = patched.json()
    assert body["waist_cm"] is None
    assert body["height_cm"] == 168.0  # 미전송 → 최신값 보존
    assert body["weight_kg"] == 63.5
    assert body["kidney_status"] == "none"


async def test_patch_without_profile_returns_404(db_client: AsyncClient) -> None:
    auth = await _guest(db_client)  # 온보딩 전 — 프로필 없음
    resp = await db_client.patch(f"{API}/health-profiles/me", json={"height_cm": 170}, headers=auth)
    assert resp.status_code == status.HTTP_404_NOT_FOUND


# 리뷰 #272-2: 유효 변경 없는 PATCH(빈 요청·동일 값)는 append-only 스냅샷을 늘리지 않도록 400.
async def test_patch_empty_body_rejected(db_client: AsyncClient) -> None:
    auth = await _guest(db_client)
    await _create_profile(db_client, auth)
    resp = await db_client.patch(f"{API}/health-profiles/me", json={}, headers=auth)
    assert resp.status_code == status.HTTP_400_BAD_REQUEST


async def test_patch_no_effective_change_rejected(db_client: AsyncClient) -> None:
    auth = await _guest(db_client)
    await _create_profile(db_client, auth)  # 키 168·몸무게 63.5·허리 84·신장 none
    # 최신과 동일한 값만 보냄 → 유효 변경 없음 → 400
    resp = await db_client.patch(
        f"{API}/health-profiles/me",
        json={"height_cm": 168, "weight_kg": 63.5, "waist_cm": 84, "kidney_status": "none"},
        headers=auth,
    )
    assert resp.status_code == status.HTTP_400_BAD_REQUEST

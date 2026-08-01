# =====================================================================================
# 회원탈퇴(DELETE /users/me) 통합 테스트.
# soft-delete라 물리 삭제는 없지만, 핵심 계약은 "탈퇴 즉시 기존 토큰이 무효화된다"는 것.
# get_user가 deleted_at IS NULL로 필터하므로, 탈퇴 후 같은 토큰 요청은 401이 되어야 한다.
# =====================================================================================
from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.base import Base
from app.models.enums import AuthProvider, OnboardingStatus
from app.models.users import User
from app.repositories.user_repository import UserRepository


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


# =====================================================================================
# #356 옵션 2: 탈퇴 = 연결 데이터 실제 삭제 + 계정 익명화, 재로그인은 복구가 아닌 신규 가입.
#   (익명화만으로는 "기록이 삭제된다"고 안내할 수 없다는 리뷰 지적 반영 — 실제 파기까지 검증한다.)
# =====================================================================================
async def _complete_onboarding(db_client: AsyncClient, auth: dict[str, str]) -> None:
    """탈퇴 대상 사용자에게 삭제될 데이터(약관 동의·세션·건강 프로필)를 만들어 둔다."""
    terms = await db_client.get("/api/v1/terms", headers=auth)  # GET /terms 도 인증 필요
    agreements = [
        {"terms_type": t["terms_type"], "version": t["version"], "agreed": True}
        for t in terms.json()["terms"]
    ]
    agree_resp = await db_client.post(
        "/api/v1/users/me/agreements", json={"agreements": agreements}, headers=auth
    )
    assert agree_resp.status_code == status.HTTP_200_OK
    session_resp = await db_client.post("/api/v1/health-check/sessions", json={"input_method": "form"}, headers=auth)
    assert session_resp.status_code == status.HTTP_201_CREATED
    profile_resp = await db_client.post(
        "/api/v1/health-profiles",
        json={
            "session_id": session_resp.json()["session_id"],
            "birth_date": "1958-03-01",
            "sex": "male",
            "height_cm": 170,
            "weight_kg": 65,
            "walk_days": 3,
            "musc_days": 1,
            "activity_input_source": "self_report",
            "input_method": "form",
            "has_estimated_value": False,
        },
        headers=auth,
    )
    assert profile_resp.status_code == status.HTTP_201_CREATED


async def test_withdraw_purges_linked_user_data(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth = await _guest_auth(db_client)
    await _complete_onboarding(db_client, auth)

    async with db_sessionmaker() as session:
        user_id = await session.scalar(select(User.user_id).order_by(User.user_id.desc()).limit(1))
        assert user_id is not None
        # 사전 조건: 삭제 대상 데이터가 실제로 쌓여 있어야 검증이 의미를 가진다.
        before = await session.scalar(
            select(func.count()).select_from(Base.metadata.tables["health_profiles"]).where(
                Base.metadata.tables["health_profiles"].c.user_id == user_id
            )
        )
        assert before and before > 0

    withdrawn = await db_client.request("DELETE", "/api/v1/users/me", json={"confirm": True}, headers=auth)
    assert withdrawn.status_code == status.HTTP_204_NO_CONTENT

    # user_id 를 직접 참조하는 **모든** 테이블에 잔존 행이 없어야 한다.
    #   메타데이터를 순회하므로, 새 테이블이 추가됐는데 purge_user_data 에 등록하지 않으면 여기서 걸린다.
    async with db_sessionmaker() as session:
        leftovers = {}
        for table in Base.metadata.sorted_tables:
            if table.name == "users" or "user_id" not in table.c:
                continue
            count = await session.scalar(
                select(func.count()).select_from(table).where(table.c.user_id == user_id)
            )
            if count:
                leftovers[table.name] = count
        assert leftovers == {}, f"탈퇴 후 잔존 데이터: {leftovers}"

        # 계정 행은 남되(탈퇴 사실·시점 보존) 개인 식별자는 익명화된다.
        user = await session.scalar(select(User).where(User.user_id == user_id))
        assert user is not None
        assert user.deleted_at is not None
        assert user.social_id.startswith("deleted:")
        assert len(user.social_id) <= 100  # VARCHAR(100) 초과 방지
        assert user.nickname == "탈퇴한 사용자"


async def test_withdrawn_social_account_relogin_creates_new_user(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """탈퇴 후 같은 소셜 계정 재로그인 = 복구가 아니라 신규 가입(#356 옵션 2).

    소셜 로그인은 외부 IdP 검증이 필요해 통합 경로로 태우기 어려우므로, 레포지토리 계약으로 확인한다:
    탈퇴로 social_id 가 익명화되면 원래 social_id 로는 어떤 행도 조회되지 않아 신규 생성 경로를 탄다.
    """
    social_id = "google-sub-356-test"
    async with db_sessionmaker() as session:
        repo = UserRepository(session)
        user = await repo.create_social_user(AuthProvider.GOOGLE, social_id, "테스터")
        await session.commit()
        original_id = user.user_id

        await repo.purge_user_data(original_id)
        await repo.soft_delete(user)
        await session.commit()

        # 원래 social_id 로는 활성 사용자도, 삭제된 사용자도 찾을 수 없다(익명화됨) → 유니크 충돌 없이 신규 생성.
        assert await repo.get_by_provider_social_id(AuthProvider.GOOGLE, social_id) is None
        recreated = await repo.create_social_user(AuthProvider.GOOGLE, social_id, "테스터")
        await session.commit()
        assert recreated.user_id != original_id
        # 신규 가입이므로 온보딩을 처음부터 다시 밟는다.
        assert recreated.onboarding_status == OnboardingStatus.PENDING

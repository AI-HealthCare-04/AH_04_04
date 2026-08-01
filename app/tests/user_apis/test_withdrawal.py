# =====================================================================================
# 회원탈퇴(DELETE /users/me) 통합 테스트.
# #356 옵션 2 + 리뷰 반영: 탈퇴 = 연결 데이터 + users 행 물리 삭제. 핵심 계약은
#   (1) 탈퇴 즉시 기존 토큰 무효화(행 미조회 → 401)
#   (2) FK 그래프에서 도달 가능한 모든 테이블의 잔존 0건
#   (3) 같은 소셜 계정 재로그인 = 신규 가입, 늦은 INSERT 는 FK 로 거부.
# =====================================================================================
from datetime import date, datetime
from decimal import Decimal
from typing import cast

import pytest
import sqlalchemy as sa
from httpx import AsyncClient
from sqlalchemy import CursorResult, func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.base import Base
from app.models.enums import AuthProvider, OnboardingStatus
from app.models.users import User
from app.repositories.user_repository import UserRepository, user_reachable_conditions


async def _guest_auth(db_client: AsyncClient) -> dict[str, str]:
    login = await db_client.post("/api/v1/auth/guest")
    return {"Authorization": f"Bearer {login.json()['access_token']}"}


async def test_withdraw_deletes_account_and_invalidates_token(db_client: AsyncClient) -> None:
    auth = await _guest_auth(db_client)

    # 탈퇴 confirm=true → 204(No Content)
    withdrawn = await db_client.request("DELETE", "/api/v1/users/me", json={"confirm": True}, headers=auth)
    assert withdrawn.status_code == status.HTTP_204_NO_CONTENT

    # 같은 토큰으로 재요청 → users 행이 삭제돼 조회 불가 → 401(탈퇴 즉시 발효)
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
# #356 옵션 2 + 리뷰 P1·P2: 탈퇴 = FK 그래프 전체 파기 + users 행 물리 삭제.
#   시드·검증 모두 user_reachable_conditions(파기와 같은 그래프)를 쓰므로,
#   새 테이블이 추가되면 자동으로 시드·파기·검증 대상에 함께 포함된다 — 등록 누락 사각지대 제거.
# =====================================================================================
async def _complete_onboarding(db_client: AsyncClient, auth: dict[str, str]) -> None:
    """탈퇴 대상 사용자에게 실제 API 경로로 기본 데이터(약관 동의·세션·건강 프로필)를 만든다."""
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


def _seed_value(column: sa.Column) -> object:
    """비-NULL·무기본값 컬럼에 넣을 최소 유효값. 새 타입이 오면 명시적으로 실패해 시드 누락을 막는다."""
    kind = column.type
    if isinstance(kind, sa.Enum):
        return kind.enums[0]
    if isinstance(kind, sa.Boolean):
        return False
    if isinstance(kind, (sa.BigInteger, sa.Integer)):
        return 1
    if isinstance(kind, sa.Numeric):
        return Decimal("1")
    if isinstance(kind, (sa.String, sa.Text)):
        return "x"
    if isinstance(kind, sa.DateTime):
        return datetime(2026, 1, 1)
    if isinstance(kind, sa.Date):
        return date(2026, 1, 1)
    if isinstance(kind, sa.JSON):
        return {}
    raise AssertionError(f"시드 값 생성 미지원 타입: {column.table.name}.{column.name} ({kind!r})")


async def _ensure_row(
    session: AsyncSession,
    table: sa.Table,
    reachable: dict[sa.Table, sa.ColumnElement[bool]],
    created_pks: dict[sa.Table, object],
) -> object:
    """table 에 시드 행 1개를 보장하고 PK 를 돌려준다. FK 부모는 재귀로 먼저 만든다."""
    if table in created_pks:
        return created_pks[table]
    condition = reachable.get(table)
    if condition is not None:
        # 온보딩 API 가 이미 만든 행(약관 동의·세션·프로필 등)은 재사용한다(UNIQUE 충돌 방지).
        pk_column = list(table.primary_key.columns)[0]
        existing = await session.scalar(select(pk_column).where(condition).limit(1))
        if existing is not None:
            created_pks[table] = existing
            return existing
    values: dict[str, object] = {}
    for column in table.columns:
        if column.primary_key:
            continue
        if column.foreign_keys:
            parent = next(iter(column.foreign_keys)).column.table
            values[column.name] = await _ensure_row(session, cast(sa.Table, parent), reachable, created_pks)
        elif not column.nullable and column.default is None and column.server_default is None:
            values[column.name] = _seed_value(column)
    result = cast(CursorResult[object], await session.execute(table.insert().values(**values)))
    pk_row = result.inserted_primary_key
    assert pk_row is not None, f"시드 INSERT 가 PK 를 돌려주지 않음: {table.name}"
    created_pks[table] = pk_row[0]
    return pk_row[0]


async def _seed_all_user_linked_tables(session: AsyncSession, user_id: int) -> None:
    """users 에서 FK 로 도달 가능한 **모든** 테이블에 이 사용자의 행을 최소 1개씩 만든다(리뷰 P2).

    mission_templates 처럼 사용자와 무관한 필수 FK 부모는 함께 만들되 파기 대상에는 들어가지
    않는다. 새 테이블이 추가되면 자동으로 시드된다 — 시드가 불가능한 구조라면 _seed_value 가
    명시적으로 실패해, '행이 없어서 통과'하는 사각지대를 막는다.
    """
    reachable = user_reachable_conditions(user_id)
    created_pks: dict[sa.Table, object] = {cast(sa.Table, User.__table__): user_id}
    for table in Base.metadata.sorted_tables:
        if table in reachable:
            await _ensure_row(session, table, reachable, created_pks)
    await session.commit()


async def test_withdraw_purges_linked_user_data(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth = await _guest_auth(db_client)
    await _complete_onboarding(db_client, auth)

    async with db_sessionmaker() as session:
        user_id = await session.scalar(select(User.user_id).order_by(User.user_id.desc()).limit(1))
        assert user_id is not None
        await _seed_all_user_linked_tables(session, user_id)
        # 사전 조건: 도달 가능한 모든 테이블에 이 사용자의 행이 실제로 있어야 파기 검증이 의미를 가진다.
        for table, condition in user_reachable_conditions(user_id).items():
            count = await session.scalar(select(func.count()).select_from(table).where(condition))
            assert count and count > 0, f"사전 데이터 시드 실패: {table.name}"

    withdrawn = await db_client.request("DELETE", "/api/v1/users/me", json={"confirm": True}, headers=auth)
    assert withdrawn.status_code == status.HTTP_204_NO_CONTENT

    # 파기와 동일한 FK 그래프로 검증: 직접(user_id)·간접(mission_log_id 등) 참조 모두 잔존 0건.
    async with db_sessionmaker() as session:
        leftovers: dict[str, int] = {}
        for table, condition in user_reachable_conditions(user_id).items():
            count = await session.scalar(select(func.count()).select_from(table).where(condition))
            if count:
                leftovers[table.name] = count
        assert leftovers == {}, f"탈퇴 후 잔존 데이터: {leftovers}"

        # users 행도 물리 삭제된다(리뷰 P1) — 늦은 INSERT 는 FK 로 거부되고, 유니크 키가 비워져
        # 같은 소셜 계정 재로그인은 신규 가입이 된다.
        assert await session.scalar(select(User).where(User.user_id == user_id)) is None


async def test_late_insert_after_withdrawal_is_rejected_by_fk(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """리뷰 P1 시나리오: 탈퇴 커밋 후 기존 user_id 로 들어오는 INSERT 는 FK 위반으로 거부돼야 한다.

    익명화 행을 남기던 이전 설계에서는 이 INSERT 가 성공해 '204 이후 데이터 잔존'이 가능했다.
    users 행을 물리 삭제하므로 DB 가 직접 막는다.
    """
    auth = await _guest_auth(db_client)
    async with db_sessionmaker() as session:
        user_id = await session.scalar(select(User.user_id).order_by(User.user_id.desc()).limit(1))
        assert user_id is not None

    withdrawn = await db_client.request("DELETE", "/api/v1/users/me", json={"confirm": True}, headers=auth)
    assert withdrawn.status_code == status.HTTP_204_NO_CONTENT

    async with db_sessionmaker() as session:
        events = Base.metadata.tables["sts_overlay_events"]
        with pytest.raises(IntegrityError):
            await session.execute(events.insert().values(user_id=user_id, tier="basic"))
            await session.commit()


async def test_withdrawn_social_account_relogin_creates_new_user(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """탈퇴 후 같은 소셜 계정 재로그인 = 복구가 아니라 신규 가입(#356 옵션 2).

    소셜 로그인은 외부 IdP 검증이 필요해 통합 경로로 태우기 어려우므로, 레포지토리 계약으로 확인한다:
    탈퇴로 users 행이 삭제되면 원래 social_id 로는 어떤 행도 조회되지 않아 신규 생성 경로를 탄다.
    """
    social_id = "google-sub-356-test"
    async with db_sessionmaker() as session:
        repo = UserRepository(session)
        user = await repo.create_social_user(AuthProvider.GOOGLE, social_id, "테스터")
        await session.commit()
        original_id = user.user_id

        await repo.purge_user_data(original_id)
        await repo.delete_account(user)
        await session.commit()

        # 행이 삭제돼 원래 social_id 로는 조회되지 않는다 → 유니크 충돌 없이 신규 생성.
        assert await repo.get_by_provider_social_id(AuthProvider.GOOGLE, social_id) is None
        recreated = await repo.create_social_user(AuthProvider.GOOGLE, social_id, "테스터")
        await session.commit()
        assert recreated.user_id != original_id
        # 신규 가입이므로 온보딩을 처음부터 다시 밟는다.
        assert recreated.onboarding_status == OnboardingStatus.PENDING

# =====================================================================================
# 통합 테스트 — 체력검사 재시도 멱등성·중단 세션 복구 (#180, 실 MySQL)
#
# 미션(create_mission_log)과 동일 패턴:
#   - COMPLETED 세션 재제출(응답 유실 재시도) → 기존 결과로 수렴(중복 레코드 없음)
#   - SKIPPED 세션 제출 → 409(성공 오인 금지)
#   - 존재하지 않는 세션 → 404
#   - 중단 후 재진입 시 start_session 이 남은 STARTED 를 재사용(새 세션 남발 금지)
# =====================================================================================
from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.health import PhysicalAssessment


async def _auth(db_client: AsyncClient) -> dict[str, str]:
    login = await db_client.post("/api/v1/auth/guest")
    return {"Authorization": f"Bearer {login.json()['access_token']}"}


async def _start_session(db_client: AsyncClient, auth: dict[str, str]) -> int:
    resp = await db_client.post("/api/v1/health-check/sessions", json={"input_method": "form"}, headers=auth)
    return int(resp.json()["session_id"])


def _assessment_body(session_id: int, sec: float = 12.4) -> dict:
    return {
        "session_id": session_id,
        "assessment_type": "initial",
        "chair_stand_5_time_sec": sec,
        "chair_stand_skipped": False,
    }


async def _count_assessments(sm: async_sessionmaker[AsyncSession], session_id: int) -> int:
    async with sm() as s:
        stmt = select(func.count()).select_from(PhysicalAssessment).where(
            PhysicalAssessment.session_id == session_id
        )
        return int(await s.scalar(stmt) or 0)


async def test_resubmit_to_completed_session_is_idempotent(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth = await _auth(db_client)
    sid = await _start_session(db_client, auth)

    first = await db_client.post("/api/v1/physical-assessments", json=_assessment_body(sid), headers=auth)
    assert first.status_code == status.HTTP_201_CREATED
    first_id = first.json()["physical_assessment_id"]

    # 응답 유실 후 동일 세션 재제출 → 새 행을 만들지 않고 기존 결과로 수렴.
    again = await db_client.post("/api/v1/physical-assessments", json=_assessment_body(sid), headers=auth)
    assert again.status_code == status.HTTP_201_CREATED
    assert again.json()["physical_assessment_id"] == first_id
    assert await _count_assessments(db_sessionmaker, sid) == 1  # 중복 없음


async def test_submit_to_skipped_session_conflicts(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth = await _auth(db_client)
    sid = await _start_session(db_client, auth)

    skip = await db_client.post(f"/api/v1/health-check/sessions/{sid}/skip", headers=auth)
    assert skip.status_code == status.HTTP_200_OK

    # 건너뛴 세션에 체력검사 → 멱등 성공으로 오인하지 않고 409.
    resp = await db_client.post("/api/v1/physical-assessments", json=_assessment_body(sid), headers=auth)
    assert resp.status_code == status.HTTP_409_CONFLICT
    assert await _count_assessments(db_sessionmaker, sid) == 0


async def test_submit_to_unknown_session_returns_404(db_client: AsyncClient) -> None:
    auth = await _auth(db_client)
    resp = await db_client.post("/api/v1/physical-assessments", json=_assessment_body(999_999_999), headers=auth)
    assert resp.status_code == status.HTTP_404_NOT_FOUND


async def test_start_session_reuses_lingering_started(db_client: AsyncClient) -> None:
    auth = await _auth(db_client)
    first_sid = await _start_session(db_client, auth)
    # 중단 후 재진입: 새 세션을 만들지 않고 기존 STARTED 를 재사용.
    second_sid = await _start_session(db_client, auth)
    assert second_sid == first_sid

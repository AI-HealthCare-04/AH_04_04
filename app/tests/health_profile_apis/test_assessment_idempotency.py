# =====================================================================================
# 통합 테스트 — 체력검사 재시도 멱등성·중단 세션 복구 (#180, 실 MySQL)
#
# 미션(create_mission_log)과 동일 패턴:
#   - COMPLETED 세션 재제출(응답 유실 재시도) → 기존 결과로 수렴(중복 레코드 없음)
#   - SKIPPED 세션 제출 → 409(성공 오인 금지)
#   - 존재하지 않는 세션 → 404
#   - 중단 후 재진입 시 start_session 이 남은 STARTED 를 재사용(새 세션 남발 금지)
# =====================================================================================
import asyncio

from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.enums import HealthCheckStatus
from app.models.health import HealthCheckSession, PhysicalAssessment


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


async def test_concurrent_submit_and_skip_serializes(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """제출과 건너뛰기가 실 MySQL 트랜잭션 두 개로 동시 도착해도 행 잠금이 직렬화하는지(#207 리뷰).

    보조 트랜잭션이 세션 행을 먼저 잠가 두 요청을 같은 잠금 대기열에 세운 뒤 풀어서,
    둘 다 확실히 동시 경합 상태에서 출발하게 만든다(gather 만으로는 한쪽이 먼저 끝나
    경합 없이 지나갈 수 있음). 승자는 MySQL 잠금 부여 순서에 따라 달라지므로 양쪽 다 검증한다.
    """
    auth = await _auth(db_client)
    sid = await _start_session(db_client, auth)

    async with db_sessionmaker() as blocker:
        await blocker.execute(
            select(HealthCheckSession).where(HealthCheckSession.session_id == sid).with_for_update()
        )
        submit_task = asyncio.create_task(
            db_client.post("/api/v1/physical-assessments", json=_assessment_body(sid), headers=auth)
        )
        skip_task = asyncio.create_task(db_client.post(f"/api/v1/health-check/sessions/{sid}/skip", headers=auth))
        await asyncio.sleep(0.5)  # 두 요청이 FOR UPDATE 대기열에 진입할 시간
        await blocker.rollback()  # 잠금 해제 → 두 트랜잭션이 순서대로 경쟁
        submit_resp, skip_resp = await asyncio.gather(submit_task, skip_task)

    # (1) 한쪽만 성공하고 다른 쪽은 409여야 한다(둘 다 성공하면 잠금 회귀).
    assert (submit_resp.status_code, skip_resp.status_code) in {
        (status.HTTP_201_CREATED, status.HTTP_409_CONFLICT),  # 제출 승리
        (status.HTTP_409_CONFLICT, status.HTTP_200_OK),  # 건너뛰기 승리
    }, f"submit={submit_resp.status_code}, skip={skip_resp.status_code}"

    # (2) 최종 세션 상태와 평가 기록 존재 여부가 일치해야 한다.
    async with db_sessionmaker() as s:
        final_status = await s.scalar(
            select(HealthCheckSession.status).where(HealthCheckSession.session_id == sid)
        )
    if submit_resp.status_code == status.HTTP_201_CREATED:
        assert final_status == HealthCheckStatus.COMPLETED
        assert await _count_assessments(db_sessionmaker, sid) == 1
    else:
        assert final_status == HealthCheckStatus.SKIPPED
        assert await _count_assessments(db_sessionmaker, sid) == 0


async def test_start_session_reuses_lingering_started(db_client: AsyncClient) -> None:
    auth = await _auth(db_client)
    first_sid = await _start_session(db_client, auth)
    # 중단 후 재진입: 새 세션을 만들지 않고 기존 STARTED 를 재사용.
    second_sid = await _start_session(db_client, auth)
    assert second_sid == first_sid

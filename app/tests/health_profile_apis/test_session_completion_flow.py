# =====================================================================================
# 통합 테스트 — 건강체크 세션의 종료 시점 검증
#
# 프로필 저장은 체력검사 전 단계이므로 세션을 종료하지 않는다.
# 명시적인 체력검사 완료 또는 건너뛰기에서만 최종 상태로 전이한다.
# =====================================================================================
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.enums import HealthCheckStatus
from app.models.health import HealthCheckSession


async def _session_status(sm: async_sessionmaker[AsyncSession], session_id: int) -> HealthCheckSession:
    async with sm() as session:
        row = await session.get(HealthCheckSession, session_id)
        assert row is not None
        return row


async def test_profile_save_keeps_session_started_until_skip(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    login = await db_client.post("/api/v1/auth/guest")
    auth = {"Authorization": f"Bearer {login.json()['access_token']}"}

    # 1) 세션 생성 직후에는 STARTED다.
    sid = (
        await db_client.post("/api/v1/health-check/sessions", json={"input_method": "form"}, headers=auth)
    ).json()["session_id"]

    row = await _session_status(db_sessionmaker, sid)
    assert row.status == HealthCheckStatus.STARTED
    assert row.completed_at is None

    # 2) 프로필 저장 후에도 건너뛰기를 선택할 수 있도록 STARTED를 유지한다.
    profile = await db_client.post(
        "/api/v1/health-profiles",
        json={
            "session_id": sid,
            "birth_date": "1955-03-10",
            "sex": "male",
            "height_cm": 160,
            "weight_kg": 58,
            "walking_practice": True,
            "strength_exercise": False,
            "activity_input_source": "self_report",
            "input_method": "form",
            "has_estimated_value": False,
        },
        headers=auth,
    )
    assert profile.status_code == status.HTTP_201_CREATED

    row = await _session_status(db_sessionmaker, sid)
    assert row.status == HealthCheckStatus.STARTED
    assert row.completed_at is None

    # 3) 실제 앱 순서대로 건너뛰면 SKIPPED로 종료된다.
    skip = await db_client.post(f"/api/v1/health-check/sessions/{sid}/skip", headers=auth)
    assert skip.status_code == status.HTTP_200_OK

    row = await _session_status(db_sessionmaker, sid)
    assert row.status == HealthCheckStatus.SKIPPED
    assert row.completed_at is not None


async def test_physical_assessment_completes_health_check_session(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    login = await db_client.post("/api/v1/auth/guest")
    auth = {"Authorization": f"Bearer {login.json()['access_token']}"}
    sid = (
        await db_client.post("/api/v1/health-check/sessions", json={"input_method": "form"}, headers=auth)
    ).json()["session_id"]

    assessment = await db_client.post(
        "/api/v1/physical-assessments",
        json={
            "session_id": sid,
            "assessment_type": "initial",
            "chair_stand_5_time_sec": 12.4,
            "chair_stand_skipped": False,
        },
        headers=auth,
    )
    assert assessment.status_code == status.HTTP_201_CREATED

    row = await _session_status(db_sessionmaker, sid)
    assert row.status == HealthCheckStatus.COMPLETED
    assert row.completed_at is not None

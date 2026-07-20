# =====================================================================================
# 회귀 통합 테스트 — 건강체크 세션의 완료 시점 검증.
#
# 세션의 최종 완료(STARTED→COMPLETED)는 프로필 저장(POST /health-profiles)이 담당한다.
# 이 테스트는 실제 MySQL(db_client)로 전 흐름을 돌려, 세션 생성 직후 STARTED이고
# 프로필 저장 후 COMPLETED가 되는지 못박는다.
# =====================================================================================
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.enums import HealthCheckStatus
from app.models.health import HealthCheckSession


async def _session_status(sm: async_sessionmaker[AsyncSession], session_id: int) -> HealthCheckSession:
    async with sm() as s:
        row = await s.get(HealthCheckSession, session_id)
        assert row is not None
        return row


async def test_profile_save_completes_health_check_session(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    login = await db_client.post("/api/v1/auth/guest")
    auth = {"Authorization": f"Bearer {login.json()['access_token']}"}

    # 1) 세션 시작 — 생성 직후에는 STARTED(아직 미완료).
    sid = (
        await db_client.post("/api/v1/health-check/sessions", json={"input_method": "form"}, headers=auth)
    ).json()["session_id"]

    row = await _session_status(db_sessionmaker, sid)
    assert row.status == HealthCheckStatus.STARTED
    assert row.completed_at is None

    # 2) 프로필 저장 = 세션의 최종 완료 시점.
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

    # 3) 이제 세션은 COMPLETED + completed_at 세팅.
    row = await _session_status(db_sessionmaker, sid)
    assert row.status == HealthCheckStatus.COMPLETED
    assert row.completed_at is not None

# =====================================================================================
# 회귀 통합 테스트 — "음성 재확인 → 프로필 저장" 흐름에서 세션 완료 시점 검증.
#
# /voice(음성 재확인)는 세션을 완료시키지 않는다(확인 단계). 세션의 최종 완료는
# 프로필 저장(POST /health-profiles)이 담당한다. 이 테스트는 실제 MySQL(db_client)로
# 전 흐름을 돌려, voice 후에도 STARTED이고 프로필 저장 후 COMPLETED가 되는지 못박는다.
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

    # 1) 세션 시작
    sid = (
        await db_client.post("/api/v1/health-check/sessions", json={"input_method": "voice"}, headers=auth)
    ).json()["session_id"]

    # 2) 음성 재확인 — 파싱 결과를 돌려주되 세션은 완료시키지 않는다(STARTED 유지).
    voice = await db_client.post(
        f"/api/v1/health-check/sessions/{sid}/voice",
        json={"field": "height_cm", "raw_transcript": "백육십"},
        headers=auth,
    )
    assert voice.status_code == status.HTTP_200_OK
    assert voice.json() == {"field": "height_cm", "value": 160.0, "needs_confirmation": True}

    row = await _session_status(db_sessionmaker, sid)
    assert row.status == HealthCheckStatus.STARTED
    assert row.completed_at is None
    assert row.raw_transcript == "백육십"

    # 3) 프로필 저장 = 세션의 최종 완료 시점.
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
            "input_method": "voice",
            "has_estimated_value": False,
        },
        headers=auth,
    )
    assert profile.status_code == status.HTTP_201_CREATED

    # 4) 이제 세션은 COMPLETED + completed_at 세팅.
    row = await _session_status(db_sessionmaker, sid)
    assert row.status == HealthCheckStatus.COMPLETED
    assert row.completed_at is not None

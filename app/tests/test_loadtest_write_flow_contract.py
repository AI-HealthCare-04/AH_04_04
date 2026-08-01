# =====================================================================================
# 부하 스크립트(scripts/bench/loadtest_k6.js) 쓰기 흐름의 서버 계약 검증 (#364, PR #371 리뷰).
#
# k6 는 CI 에서 실행하지 않으므로, 스크립트가 WRITES=1 에서 보내는 것과 **동일한 페이로드
# 시퀀스**를 통합 픽스처로 재연해 계약 위반(400·409)이 실측 결과를 오염시키지 않게 고정한다:
#   게스트 로그인 → GET /missions?status=available → 걷기 미션 선택
#   → POST /mission-logs (in_progress) → PATCH /mission-logs/{id} (completed + walking_detail)
# ⚠️ loadtest_k6.js 의 페이로드를 바꾸면 이 테스트도 함께 갱신할 것(양쪽 주석에 상호 링크).
# =====================================================================================
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.enums import ActivityLevel, MissionType, TargetUnit
from app.models.missions import MissionTemplate

API = "/api/v1"


async def _seed_walking_template(sm: async_sessionmaker[AsyncSession]) -> int:
    async with sm() as s:
        template = MissionTemplate(
            mission_type=MissionType.WALKING,
            title="걷기 미션",
            level=ActivityLevel.EASY,
            display_order=1,
            default_target_value=30,
            target_unit=TargetUnit.MINUTES,
            reward_points=10,
            daily_count_limit=None,
        )
        s.add(template)
        await s.flush()
        template_id = template.mission_template_id
        await s.commit()
    return template_id


async def test_k6_write_flow_payloads_match_server_contract(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    await _seed_walking_template(db_sessionmaker)

    # k6: ensureLogin() — 게스트 토큰 1회 발급
    login = await db_client.post(f"{API}/auth/guest")
    assert login.status_code == status.HTTP_200_OK
    auth = {"Authorization": f"Bearer {login.json()['access_token']}"}

    # k6: GET /missions?status=available → mission_type == "walking" 선택
    missions = await db_client.get(f"{API}/missions?status=available", headers=auth)
    assert missions.status_code == status.HTTP_200_OK
    walking = next(
        (m for m in missions.json()["missions"] if m["mission_type"] == "walking"), None
    )
    assert walking is not None, "걷기 템플릿이 목록에 없으면 스크립트 쓰기 흐름이 통째로 스킵된다"

    # k6: POST /mission-logs — createBody 와 동일한 페이로드
    created = await db_client.post(
        f"{API}/mission-logs",
        json={
            "mission_template_id": walking["mission_template_id"],
            "mission_type": "walking",
            "status": "in_progress",
        },
        headers=auth,
    )
    assert created.status_code in (status.HTTP_200_OK, status.HTTP_201_CREATED), created.text
    log_id = created.json()["mission_log_id"]

    # k6: PATCH /mission-logs/{id} — patchBody 와 동일한 페이로드
    #   (success 는 보내지 않는다 — 걷기 성공은 서버가 당일 누적으로 판정)
    patched = await db_client.patch(
        f"{API}/mission-logs/{log_id}",
        json={"status": "completed", "walking_detail": {"duration_min": 5, "steps": 600}},
        headers=auth,
    )
    assert patched.status_code == status.HTTP_200_OK, patched.text

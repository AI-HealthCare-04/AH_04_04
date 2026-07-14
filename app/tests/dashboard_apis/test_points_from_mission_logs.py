# =====================================================================================
# 포인트(잔액·적립이력) 파생 통합 테스트 (실 MySQL).
# 잔액 = SUM(mission_logs.earned_points), 적립이력 = earned_points>0인 미션 로그.
# 별도 point_balances/point_earn_logs 테이블 없이 미션 로그가 단일 원천임을 검증한다.
# =====================================================================================
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.enums import ActivityLevel, MissionStatus, MissionType, TargetUnit
from app.models.missions import MissionLog, MissionTemplate


async def _guest(db_client: AsyncClient) -> tuple[dict[str, str], int]:
    login = await db_client.post("/api/v1/auth/guest")
    body = login.json()
    return {"Authorization": f"Bearer {body['access_token']}"}, body["user"]["user_id"]


async def _seed_earnings(sm: async_sessionmaker[AsyncSession], user_id: int, points: list[int]) -> None:
    async with sm() as s:
        template = MissionTemplate(
            mission_type=MissionType.GAME,
            title="카드 짝 맞추기",
            level=ActivityLevel.NORMAL,
            display_order=1,
            default_target_value=1,
            target_unit=TargetUnit.COUNT,
            reward_points=5,
        )
        s.add(template)
        await s.flush()
        for earned in points:
            s.add(
                MissionLog(
                    user_id=user_id,
                    mission_template_id=template.mission_template_id,
                    mission_type=MissionType.GAME,
                    status=MissionStatus.COMPLETED,
                    success=earned > 0,
                    earned_points=earned,
                )
            )
        await s.commit()


async def test_points_balance_and_earn_logs_derive_from_mission_logs(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    # 적립 10, 5 (0은 미적립 → 이력 제외)
    await _seed_earnings(db_sessionmaker, user_id, [10, 5, 0])

    resp = await db_client.get("/api/v1/users/me/points", headers=auth)
    assert resp.status_code == status.HTTP_200_OK
    body = resp.json()

    # 잔액 = 적립 총합
    assert body["current_points"] == 15
    # 적립 이력 = earned_points>0 인 것만(2건), 각 항목 계약 준수
    assert len(body["earn_logs"]) == 2
    assert {log["earned_points"] for log in body["earn_logs"]} == {10, 5}
    for log in body["earn_logs"]:
        assert log["reason"] == "game"
        assert isinstance(log["earn_id"], int)
        assert log["created_at"].endswith("+09:00")  # KstDatetime

    # 홈에도 실제 잔액이 반영된다
    home = await db_client.get("/api/v1/home", headers=auth)
    assert home.json()["point_balance"]["current_points"] == 15


async def test_points_zero_when_no_earnings(db_client: AsyncClient) -> None:
    auth, _ = await _guest(db_client)
    resp = await db_client.get("/api/v1/users/me/points", headers=auth)
    assert resp.json() == {"current_points": 0, "earn_logs": []}

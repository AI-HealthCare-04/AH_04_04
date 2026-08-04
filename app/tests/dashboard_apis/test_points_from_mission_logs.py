# =====================================================================================
# 포인트 잔액 파생 통합 테스트 (실 MySQL).
# 잔액 = SUM(mission_logs.earned_points). 별도 point_balances 테이블 없이
# 미션 로그가 단일 원천임을 검증한다. (포인트 조회 API 제거 이후에는 홈 point_balance
# 와 DashboardRepository.get_current_points 가 유일한 노출 경로다.)
# =====================================================================================
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.enums import ActivityLevel, MissionStatus, MissionType, TargetUnit
from app.models.missions import MissionLog, MissionTemplate
from app.repositories.dashboard_repository import DashboardRepository


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


async def test_points_balance_derives_from_mission_logs(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    # 적립 10, 5 (0은 잔액에 영향 없음)
    await _seed_earnings(db_sessionmaker, user_id, [10, 5, 0])

    # 저장소 잔액 = 적립 총합
    async with db_sessionmaker() as s:
        assert await DashboardRepository(s).get_current_points(user_id) == 15

    # 홈에도 실제 잔액이 반영된다
    home = await db_client.get("/api/v1/home", headers=auth)
    assert home.status_code == status.HTTP_200_OK
    assert home.json()["point_balance"]["current_points"] == 15


async def test_points_zero_when_no_earnings(db_client: AsyncClient) -> None:
    auth, _ = await _guest(db_client)
    home = await db_client.get("/api/v1/home", headers=auth)
    assert home.json()["point_balance"]["current_points"] == 0

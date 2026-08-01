# =====================================================================================
# 모든 미션 완료 보너스 E2E 통합 테스트 (실 MySQL).
#
# 미션 탭 하단 카드가 약속하는 "모든 미션을 완료하면 추가 보너스 포인트를 드려요!"의 계약을 고정한다:
#   - 그날 '보이는' 미션 종류를 전부 채우면 보너스 1회 적립 (BONUS_POINTS)
#   - 하나라도 남으면 미적립
#   - 하루에 한 번만 (같은 날 추가 완료로 중복 적립되지 않음)
#   - 보너스는 일일 판정(daily_result)에는 세지 않는다 — 사용자가 '수행한' 미션 수가 아니므로
#   - 포인트 잔액·적립 이력(GET /points)에 자동으로 잡힌다(mission_logs 단일 원천)
# =====================================================================================
from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.enums import ActivityLevel, MissionType, TargetUnit
from app.models.missions import MissionLog, MissionTemplate
from app.services.mission_scoring import BONUS_POINTS

API = "/api/v1"

MEAL_POINTS = 10
GAME_POINTS = 5


async def _guest(db_client: AsyncClient) -> tuple[dict[str, str], int]:
    login = await db_client.post(f"{API}/auth/guest")
    body = login.json()
    return {"Authorization": f"Bearer {body['access_token']}"}, body["user"]["user_id"]


async def _seed_template(
    sm: async_sessionmaker[AsyncSession],
    *,
    mission_type: MissionType,
    reward_points: int,
    daily_count_limit: int | None = None,
    display_order: int = 1,
) -> int:
    """미션 템플릿 1건 시드. 게스트는 활동 레벨 프로필이 없어 EASY 로 목록이 잡히므로 EASY 로 둔다."""
    async with sm() as s:
        template = MissionTemplate(
            mission_type=mission_type,
            title=f"{mission_type.value} 미션",
            level=ActivityLevel.EASY,
            display_order=display_order,
            default_target_value=1,
            target_unit=TargetUnit.COUNT,
            reward_points=reward_points,
            daily_count_limit=daily_count_limit,
        )
        s.add(template)
        await s.flush()
        template_id = template.mission_template_id
        await s.commit()
    return template_id


async def _seed_bonus_template(sm: async_sessionmaker[AsyncSession]) -> int:
    """보너스 템플릿(운영에서는 마이그레이션 0020 이 넣는 행). 테스트 DB 는 매번 비워지므로 여기서 시드한다."""
    return await _seed_template(
        sm, mission_type=MissionType.BONUS, reward_points=BONUS_POINTS, daily_count_limit=1, display_order=90
    )


def _meal_body(template_id: int) -> dict:
    return {
        "mission_template_id": template_id,
        "mission_type": "meal",
        "status": "completed",
        "success": True,
        "meal_detail": {"protein_foods": ["meat", "egg"]},
    }


def _game_body(template_id: int) -> dict:
    return {
        "mission_template_id": template_id,
        "mission_type": "game",
        "status": "completed",
        "success": True,
        "game_detail": {"game_type": "card_match", "completed": True},
    }


async def _bonus_logs(sm: async_sessionmaker[AsyncSession], user_id: int) -> int:
    async with sm() as s:
        stmt = (
            select(func.count())
            .select_from(MissionLog)
            .where(MissionLog.user_id == user_id, MissionLog.mission_type == MissionType.BONUS)
        )
        return (await s.scalar(stmt)) or 0


# -------------------------------------------------------------------------------------
# 1. 보이는 미션을 전부 채우면 보너스가 붙는다
# -------------------------------------------------------------------------------------
async def test_bonus_awarded_when_every_visible_mission_is_complete(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    await _seed_bonus_template(db_sessionmaker)
    meal_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.MEAL, reward_points=MEAL_POINTS, daily_count_limit=1
    )
    game_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.GAME, reward_points=GAME_POINTS, display_order=2
    )

    # 식사만 끝낸 시점 — 게임이 남았으므로 아직 보너스 없음
    first = await db_client.post(f"{API}/mission-logs", json=_meal_body(meal_id), headers=auth)
    assert first.status_code == status.HTTP_201_CREATED
    assert await _bonus_logs(db_sessionmaker, user_id) == 0
    assert (await db_client.get(f"{API}/users/me/points", headers=auth)).json()["current_points"] == MEAL_POINTS

    # 게임까지 끝내 '보이는 미션'(식사·게임)을 모두 채움 → 보너스 적립
    second = await db_client.post(f"{API}/mission-logs", json=_game_body(game_id), headers=auth)
    assert second.status_code == status.HTTP_201_CREATED
    assert await _bonus_logs(db_sessionmaker, user_id) == 1

    points = (await db_client.get(f"{API}/users/me/points", headers=auth)).json()
    assert points["current_points"] == MEAL_POINTS + GAME_POINTS + BONUS_POINTS
    # 적립 이력에도 보너스가 따로 남는다(reason 이 mission_type 이라 'bonus' 로 구분된다).
    reasons = [log["reason"] for log in points["earn_logs"]]
    assert reasons.count("bonus") == 1


# -------------------------------------------------------------------------------------
# 2. 하루에 한 번만 — 같은 날 더 수행해도 다시 붙지 않는다
# -------------------------------------------------------------------------------------
async def test_bonus_is_awarded_only_once_a_day(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    await _seed_bonus_template(db_sessionmaker)
    meal_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.MEAL, reward_points=MEAL_POINTS, daily_count_limit=1
    )
    game_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.GAME, reward_points=GAME_POINTS, display_order=2
    )

    await db_client.post(f"{API}/mission-logs", json=_meal_body(meal_id), headers=auth)
    await db_client.post(f"{API}/mission-logs", json=_game_body(game_id), headers=auth)
    assert await _bonus_logs(db_sessionmaker, user_id) == 1
    after_first = (await db_client.get(f"{API}/users/me/points", headers=auth)).json()["current_points"]

    # 게임은 일일 한도가 없어 또 할 수 있다 — 게임 포인트는 늘지만 보너스는 그대로 1회.
    again = await db_client.post(f"{API}/mission-logs", json=_game_body(game_id), headers=auth)
    assert again.status_code == status.HTTP_201_CREATED
    assert await _bonus_logs(db_sessionmaker, user_id) == 1
    assert (await db_client.get(f"{API}/users/me/points", headers=auth)).json()["current_points"] == after_first + GAME_POINTS


# -------------------------------------------------------------------------------------
# 3. 보너스는 '수행한 미션'이 아니라 적립이므로 일일 판정에 세지 않는다
# -------------------------------------------------------------------------------------
async def test_bonus_does_not_count_toward_daily_result(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, _ = await _guest(db_client)
    await _seed_bonus_template(db_sessionmaker)
    meal_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.MEAL, reward_points=MEAL_POINTS, daily_count_limit=1
    )
    game_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.GAME, reward_points=GAME_POINTS, display_order=2
    )

    await db_client.post(f"{API}/mission-logs", json=_meal_body(meal_id), headers=auth)
    last = await db_client.post(f"{API}/mission-logs", json=_game_body(game_id), headers=auth)

    # 실제 수행은 2건 → 대성공(3건) 기준에 못 미친다. 보너스가 세어졌다면 great_success 가 됐을 것이다.
    assert last.json()["daily_result"] == "success"
    home = (await db_client.get(f"{API}/home", headers=auth)).json()
    assert home["today_summary"]["counted_mission_count"] == 2
    # 요약의 적립 포인트에는 보너스가 포함된다(포인트는 지급됐다).
    assert home["point_balance"]["current_points"] == MEAL_POINTS + GAME_POINTS + BONUS_POINTS


# -------------------------------------------------------------------------------------
# 4. 보너스는 일반 생성 API 로 직접 적립할 수 없다 (서버 내부 지급 전용)
# -------------------------------------------------------------------------------------
async def test_bonus_cannot_be_claimed_through_the_public_api(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """보너스 템플릿 id 로 completed 를 직접 보내면 거부되고, 로그도 포인트도 생기지 않아야 한다.

    막지 않으면 인증된 사용자가 10점을 임의 적립할 수 있고, 자연 키(created_on_device_at)만
    바꿔 반복 지급까지 가능하다. 종류별 status 검증은 BONUS 를 어느 집합에도 넣지 않아 걸러내지 못한다.
    """
    auth, user_id = await _guest(db_client)
    bonus_template_id = await _seed_bonus_template(db_sessionmaker)

    body = {
        "mission_template_id": bonus_template_id,
        "mission_type": "bonus",
        "status": "completed",
        "success": True,
    }
    resp = await db_client.post(f"{API}/mission-logs", json=body, headers=auth)
    assert resp.status_code == status.HTTP_400_BAD_REQUEST

    assert await _bonus_logs(db_sessionmaker, user_id) == 0
    assert (await db_client.get(f"{API}/users/me/points", headers=auth)).json()["current_points"] == 0

    # 자연 키를 바꿔 다시 보내도 마찬가지 — 재전송 조회로 우회되지 않는다.
    retry = await db_client.post(
        f"{API}/mission-logs", json={**body, "created_on_device_at": "2026-08-01T10:11:12.123456+09:00"}, headers=auth
    )
    assert retry.status_code == status.HTTP_400_BAD_REQUEST
    assert await _bonus_logs(db_sessionmaker, user_id) == 0


# -------------------------------------------------------------------------------------
# 5. 보너스 템플릿은 미션 목록에 나오지 않는다
# -------------------------------------------------------------------------------------
async def test_bonus_template_is_hidden_from_mission_list(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, _ = await _guest(db_client)
    await _seed_bonus_template(db_sessionmaker)
    await _seed_template(
        db_sessionmaker, mission_type=MissionType.GAME, reward_points=GAME_POINTS
    )

    missions = (await db_client.get(f"{API}/missions", headers=auth)).json()["missions"]
    assert [m["mission_type"] for m in missions] == ["game"]

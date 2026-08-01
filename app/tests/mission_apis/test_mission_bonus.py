# =====================================================================================
# 모든 미션 완료 보너스 E2E 통합 테스트 (실 MySQL).
#
# 미션 탭 하단 카드가 약속하는 "모든 미션을 완료하면 추가 보너스 포인트를 드려요!"의 계약을 고정한다:
#   - 그날 '보이는' 미션 종류를 전부 채우면 보너스 1회 적립 (BONUS_POINTS)
#   - 하나라도 남으면 미적립
#   - 단, **완료 경로가 없는 종류(v1 게임)는 조건에서 제외**한다(#378) — 안 그러면 게임 템플릿이
#     활성인 한 어떤 사용자도 보너스를 받을 수 없다(게임은 counted_for_daily 로 잡히지 않음)
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
    target_unit: TargetUnit = TargetUnit.COUNT,
) -> int:
    """미션 템플릿 1건 시드. 게스트는 활동 레벨 프로필이 없어 EASY 로 목록이 잡히므로 EASY 로 둔다."""
    async with sm() as s:
        template = MissionTemplate(
            mission_type=mission_type,
            title=f"{mission_type.value} 미션",
            level=ActivityLevel.EASY,
            display_order=display_order,
            default_target_value=1,
            target_unit=target_unit,
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


WALKING_POINTS = 5


async def _seed_visible_set(sm: async_sessionmaker[AsyncSession]) -> tuple[int, int, int]:
    """실제 RC 와 같은 구성으로 시드한다: 식사 + 걷기 + **게임**(활성이지만 완료 경로 없음, #378).

    게임 템플릿을 일부러 섞어 두는 것이 이 파일의 핵심 회귀 방어다 — 게임이 required 에 남으면
    보너스가 영원히 지급되지 않는다.
    """
    await _seed_bonus_template(sm)
    meal_id = await _seed_template(
        sm, mission_type=MissionType.MEAL, reward_points=MEAL_POINTS, daily_count_limit=1
    )
    walking_id = await _seed_template(
        sm,
        mission_type=MissionType.WALKING,
        reward_points=WALKING_POINTS,
        display_order=2,
        target_unit=TargetUnit.MINUTES,
    )
    game_id = await _seed_template(
        sm, mission_type=MissionType.GAME, reward_points=GAME_POINTS, display_order=3
    )
    return meal_id, walking_id, game_id


async def _complete_walking(
    db_client: AsyncClient, auth: dict[str, str], template_id: int, minutes: float = 10
) -> dict:
    """걷기는 시작(in_progress) → 종료(completed + walking_detail) 2단계다. 목표 1분이라 성공·카운트된다."""
    start = await db_client.post(
        f"{API}/mission-logs",
        json={"mission_template_id": template_id, "mission_type": "walking", "status": "in_progress"},
        headers=auth,
    )
    log_id = start.json()["mission_log_id"]
    done = await db_client.patch(
        f"{API}/mission-logs/{log_id}",
        json={"status": "completed", "walking_detail": {"duration_min": minutes, "steps": 1200}},
        headers=auth,
    )
    assert done.status_code == status.HTTP_200_OK
    return done.json()


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
    meal_id, walking_id, _game_id = await _seed_visible_set(db_sessionmaker)

    # 식사만 끝낸 시점 — 걷기가 남았으므로 아직 보너스 없음
    first = await db_client.post(f"{API}/mission-logs", json=_meal_body(meal_id), headers=auth)
    assert first.status_code == status.HTTP_201_CREATED
    assert await _bonus_logs(db_sessionmaker, user_id) == 0
    assert (await db_client.get(f"{API}/users/me/points", headers=auth)).json()["current_points"] == MEAL_POINTS

    # 걷기까지 끝내 완료 경로가 있는 미션을 모두 채움 → 보너스 적립
    #   (게임 템플릿도 활성이지만 완료하지 않았다 — 그래도 보너스가 나와야 한다, #378)
    await _complete_walking(db_client, auth, walking_id)
    assert await _bonus_logs(db_sessionmaker, user_id) == 1

    points = (await db_client.get(f"{API}/users/me/points", headers=auth)).json()
    assert points["current_points"] == MEAL_POINTS + WALKING_POINTS + BONUS_POINTS
    # 적립 이력에도 보너스가 따로 남는다(reason 이 mission_type 이라 'bonus' 로 구분된다).
    reasons = [log["reason"] for log in points["earn_logs"]]
    assert reasons.count("bonus") == 1


# -------------------------------------------------------------------------------------
# 1-b. (#378 회귀) 완료 경로가 없는 게임이 활성이어도 보너스는 막히지 않는다
# -------------------------------------------------------------------------------------
async def test_active_game_template_does_not_block_bonus(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """RC 에서 보너스 지급이 0건이던 원인(#378)의 회귀 방어.

    게임은 v1 에서 완료 처리(counted_for_daily)가 구현되지 않아 counted 집합에 절대 들어가지 못한다.
    그런데 템플릿은 활성이라 required 에는 들어가므로, 제외하지 않으면 **어떤 사용자도** 보너스를
    받을 수 없다. 게임을 한 번도 완료하지 않은 상태에서 나머지를 채우면 보너스가 나와야 한다.
    """
    auth, user_id = await _guest(db_client)
    meal_id, walking_id, game_id = await _seed_visible_set(db_sessionmaker)

    # 게임 템플릿이 목록에 실제로 보이는(=required 후보인) 상태임을 먼저 고정한다.
    missions = (await db_client.get(f"{API}/missions", headers=auth)).json()["missions"]
    assert "game" in [m["mission_type"] for m in missions]

    await db_client.post(f"{API}/mission-logs", json=_meal_body(meal_id), headers=auth)
    await _complete_walking(db_client, auth, walking_id)

    assert await _bonus_logs(db_sessionmaker, user_id) == 1, "게임 미완료로 보너스가 막히면 안 된다(#378)"

    # 게임을 실제로 완료해도(서버 API 로는 가능) 보너스가 중복 지급되지는 않는다.
    await db_client.post(f"{API}/mission-logs", json=_game_body(game_id), headers=auth)
    assert await _bonus_logs(db_sessionmaker, user_id) == 1


# -------------------------------------------------------------------------------------
# 2. 하루에 한 번만 — 같은 날 더 수행해도 다시 붙지 않는다
# -------------------------------------------------------------------------------------
async def test_bonus_is_awarded_only_once_a_day(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    meal_id, walking_id, _game_id = await _seed_visible_set(db_sessionmaker)

    await db_client.post(f"{API}/mission-logs", json=_meal_body(meal_id), headers=auth)
    await _complete_walking(db_client, auth, walking_id)
    assert await _bonus_logs(db_sessionmaker, user_id) == 1
    after_first = (await db_client.get(f"{API}/users/me/points", headers=auth)).json()["current_points"]

    # 걷기는 일일 한도가 없어 또 할 수 있다 — 다만 목표를 이미 넘겨 추가 적립은 없고, 보너스도 1회 그대로.
    await _complete_walking(db_client, auth, walking_id)
    assert await _bonus_logs(db_sessionmaker, user_id) == 1
    assert (await db_client.get(f"{API}/users/me/points", headers=auth)).json()["current_points"] == after_first


# -------------------------------------------------------------------------------------
# 3. 보너스는 '수행한 미션'이 아니라 적립이므로 일일 판정에 세지 않는다
# -------------------------------------------------------------------------------------
async def test_bonus_does_not_count_toward_daily_result(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, _ = await _guest(db_client)
    meal_id, walking_id, _game_id = await _seed_visible_set(db_sessionmaker)

    await db_client.post(f"{API}/mission-logs", json=_meal_body(meal_id), headers=auth)
    last = await _complete_walking(db_client, auth, walking_id)

    # 실제 수행은 2건 → 대성공(3건) 기준에 못 미친다. 보너스가 세어졌다면 great_success 가 됐을 것이다.
    assert last["daily_result"] == "success"
    home = (await db_client.get(f"{API}/home", headers=auth)).json()
    assert home["today_summary"]["counted_mission_count"] == 2
    # 요약의 적립 포인트에는 보너스가 포함된다(포인트는 지급됐다).
    assert home["point_balance"]["current_points"] == MEAL_POINTS + WALKING_POINTS + BONUS_POINTS


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

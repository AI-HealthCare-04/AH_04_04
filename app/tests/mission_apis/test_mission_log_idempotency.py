# =====================================================================================
# 오프라인 재전송 멱등성 계약 (실 MySQL).
#
# 앱의 outbox 가 응답을 못 받고 같은 수행을 다시 보내도 기록이 두 건이 되면 안 된다(#91, #105).
# 걷기 성공 판정이 '당일 로그 합산'이라, 중복 한 건이 곧 목표 달성·포인트 지급으로 이어진다.
#
# 여기서 고정하는 계약:
#   - created_on_device_at 이 같으면 → 새로 만들지 않고 기존 것을 200 으로 반환(deduplicated=true)
#   - created_on_device_at 이 다르면 → 정상 삽입(걷기·운동·게임은 반복 수행 허용)
#   - created_on_device_at 이 없으면 → 종전대로 매번 삽입(이 값을 안 보내는 기존 앱 호환)
#   - 재전송 2건이 동시에 도착해도 → 유니크 제약이 막아 한 건만 남는다
# =====================================================================================
from datetime import datetime

import pytest
from httpx import AsyncClient
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from starlette import status

from app.models.enums import ActivityLevel, MissionStatus, MissionType, TargetUnit
from app.models.missions import MissionLog, MissionTemplate

API = "/api/v1"

# 앱이 "이 기록을 만든 시각"으로 보내는 값. 재전송이면 이 값이 같다.
DEVICE_TIME = "2026-07-22T10:00:00.123456+09:00"
OTHER_DEVICE_TIME = "2026-07-22T14:30:00.654321+09:00"


async def _guest(db_client: AsyncClient) -> tuple[dict[str, str], int]:
    login = await db_client.post(f"{API}/auth/guest")
    body = login.json()
    return {"Authorization": f"Bearer {body['access_token']}"}, body["user"]["user_id"]


async def _seed_template(
    sm: async_sessionmaker[AsyncSession],
    *,
    mission_type: MissionType,
    reward_points: int = 10,
    daily_count_limit: int | None = None,
) -> int:
    async with sm() as s:
        template = MissionTemplate(
            mission_type=mission_type,
            title=f"{mission_type.value} 멱등 테스트",
            level=ActivityLevel.EASY,
            display_order=1,
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


async def _count_logs(sm: async_sessionmaker[AsyncSession], user_id: int) -> int:
    async with sm() as s:
        return (await s.scalar(select(func.count()).select_from(MissionLog).where(MissionLog.user_id == user_id))) or 0


def _game_body(template_id: int, device_time: str | None) -> dict:
    body: dict = {
        "mission_template_id": template_id,
        "mission_type": "game",
        "status": "completed",
        "success": True,
        "game_detail": {"game_type": "card_match", "completed": True},
    }
    if device_time is not None:
        body["created_on_device_at"] = device_time
    return body


# -------------------------------------------------------------------------------------
# 1. 같은 기기 시각으로 재전송 → 새로 만들지 않고 기존 것을 돌려준다
# -------------------------------------------------------------------------------------
async def test_resend_with_same_device_time_returns_existing_log(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    template_id = await _seed_template(db_sessionmaker, mission_type=MissionType.GAME, reward_points=5)

    first = await db_client.post(f"{API}/mission-logs", json=_game_body(template_id, DEVICE_TIME), headers=auth)
    assert first.status_code == status.HTTP_201_CREATED
    assert first.json()["deduplicated"] is False

    # 앱이 응답을 못 받았다고 보고 그대로 재전송한다.
    again = await db_client.post(f"{API}/mission-logs", json=_game_body(template_id, DEVICE_TIME), headers=auth)

    assert again.status_code == status.HTTP_200_OK  # 생성이 아니므로 201 이 아니다
    assert again.json()["deduplicated"] is True
    assert again.json()["mission_log_id"] == first.json()["mission_log_id"]

    assert await _count_logs(db_sessionmaker, user_id) == 1

    # 포인트가 두 번 적립되지 않는다.
    points = (await db_client.get(f"{API}/users/me/points", headers=auth)).json()
    assert points["current_points"] == 5
    assert len(points["earn_logs"]) == 1


# -------------------------------------------------------------------------------------
# 2. 기기 시각이 다르면 반복 수행으로 보고 정상 삽입한다
# -------------------------------------------------------------------------------------
async def test_different_device_time_is_a_new_performance(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    template_id = await _seed_template(db_sessionmaker, mission_type=MissionType.GAME, reward_points=5)

    first = await db_client.post(f"{API}/mission-logs", json=_game_body(template_id, DEVICE_TIME), headers=auth)
    second = await db_client.post(f"{API}/mission-logs", json=_game_body(template_id, OTHER_DEVICE_TIME), headers=auth)

    assert first.status_code == status.HTTP_201_CREATED
    assert second.status_code == status.HTTP_201_CREATED
    assert second.json()["deduplicated"] is False
    assert second.json()["mission_log_id"] != first.json()["mission_log_id"]

    # 게임은 일일 한도가 없어 두 번 다 카운트된다 — 멱등 처리가 정당한 반복을 막지 않는다.
    assert await _count_logs(db_sessionmaker, user_id) == 2


# -------------------------------------------------------------------------------------
# 3. 기기 시각을 안 보내면 종전대로 동작한다 (이 값을 모르는 기존 앱 호환)
# -------------------------------------------------------------------------------------
async def test_without_device_time_behaviour_is_unchanged(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    template_id = await _seed_template(db_sessionmaker, mission_type=MissionType.GAME, reward_points=5)

    first = await db_client.post(f"{API}/mission-logs", json=_game_body(template_id, None), headers=auth)
    second = await db_client.post(f"{API}/mission-logs", json=_game_body(template_id, None), headers=auth)

    # NULL 은 MySQL 유니크에서 제외되므로 둘 다 생성된다.
    assert first.status_code == status.HTTP_201_CREATED
    assert second.status_code == status.HTTP_201_CREATED
    assert await _count_logs(db_sessionmaker, user_id) == 2


# -------------------------------------------------------------------------------------
# 4. 조회를 통과해도 DB 가 막는다 — 서비스의 사전 조회만으로는 경합을 못 막는다
# -------------------------------------------------------------------------------------
async def test_unique_constraint_blocks_duplicate_at_db_level(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    """재전송 2건이 동시에 도착하면 둘 다 '없음'을 보고 삽입으로 넘어갈 수 있다.

    그 경합은 서비스 코드로는 못 막고 유니크 제약만 막는다. 여기서는 서로 다른 세션으로
    같은 키를 넣어 DB 가 실제로 거부하는지 확인한다(서비스는 이 예외를 받아 기존 것을 돌려준다).
    """
    _, user_id = await _guest(db_client)
    template_id = await _seed_template(db_sessionmaker, mission_type=MissionType.GAME, reward_points=5)
    device_time = datetime.fromisoformat(DEVICE_TIME)

    def _log() -> MissionLog:
        return MissionLog(
            user_id=user_id,
            mission_template_id=template_id,
            mission_type=MissionType.GAME,
            status=MissionStatus.COMPLETED,
            success=True,
            counted_for_daily=True,
            earned_points=5,
            created_on_device_at=device_time,
        )

    async with db_sessionmaker() as first:
        first.add(_log())
        await first.commit()

    async with db_sessionmaker() as second:
        second.add(_log())
        with pytest.raises(IntegrityError):
            await second.commit()

    assert await _count_logs(db_sessionmaker, user_id) == 1


# -------------------------------------------------------------------------------------
# 5. 식사 재전송 — 1일 1회 카운트가 흔들리지 않는다
# -------------------------------------------------------------------------------------
async def test_meal_resend_keeps_daily_count_intact(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    template_id = await _seed_template(
        db_sessionmaker, mission_type=MissionType.MEAL, reward_points=10, daily_count_limit=1
    )
    body = {
        "mission_template_id": template_id,
        "mission_type": "meal",
        "status": "completed",
        "success": True,
        "created_on_device_at": DEVICE_TIME,
        "meal_detail": {"protein_foods": ["두부"], "protein_meal_count": 1},
    }

    first = await db_client.post(f"{API}/mission-logs", json=body, headers=auth)
    again = await db_client.post(f"{API}/mission-logs", json=body, headers=auth)

    assert first.json()["counted_for_daily"] is True
    assert again.status_code == status.HTTP_200_OK
    assert again.json()["counted_for_daily"] is True  # 최초 수행의 결과를 그대로 돌려준다
    assert again.json()["daily_limit_reached"] is False  # 재전송은 '한도 초과'가 아니다

    assert await _count_logs(db_sessionmaker, user_id) == 1
    home = (await db_client.get(f"{API}/home", headers=auth)).json()
    assert home["point_balance"]["current_points"] == 10
    assert home["today_summary"]["counted_mission_count"] == 1


# -------------------------------------------------------------------------------------
# 6. 걷기 시작(in_progress) 재전송 — 세션이 두 개로 갈라지지 않는다
# -------------------------------------------------------------------------------------
async def test_walking_start_resend_does_not_split_session(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    auth, user_id = await _guest(db_client)
    template_id = await _seed_template(db_sessionmaker, mission_type=MissionType.WALKING, reward_points=10)
    body = {
        "mission_template_id": template_id,
        "mission_type": "walking",
        "status": "in_progress",
        "created_on_device_at": DEVICE_TIME,
    }

    first = await db_client.post(f"{API}/mission-logs", json=body, headers=auth)
    again = await db_client.post(f"{API}/mission-logs", json=body, headers=auth)

    assert first.status_code == status.HTTP_201_CREATED
    assert again.status_code == status.HTTP_200_OK
    # 같은 로그를 가리켜야 이어지는 PATCH(걷기 종료)가 한 세션에만 반영된다.
    assert again.json()["mission_log_id"] == first.json()["mission_log_id"]
    assert await _count_logs(db_sessionmaker, user_id) == 1

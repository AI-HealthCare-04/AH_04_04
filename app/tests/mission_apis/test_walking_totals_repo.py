# =====================================================================================
# '오늘 걷기 누적'(분·걸음) 집계 회귀 테스트 — 홈/걷기완료 공용 원천.
#
# 분(daily_total_min·달성 판정)과 걸음(daily_total_steps·표시)을 한 SELECT(동일 statement
# snapshot)로 함께 읽어, 두 값이 서로 다른 시점에서 집계돼 어긋난 쌍(예: 이전 분 + 최신 걸음)이
# 나오지 않게 한다. (지영 리뷰 #69: READ COMMITTED에서 두 개의 순차 SELECT는 torn-read 위험)
# =====================================================================================
import asyncio
from decimal import Decimal
from types import SimpleNamespace
from typing import cast

from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.core.utils.clock import today_kst
from app.models.enums import (
    ActivityLevel,
    ActivitySource,
    ActivityType,
    MissionStatus,
    MissionType,
    SyncStatus,
    TargetUnit,
)
from app.models.missions import MissionLog, MissionTemplate, PhysicalActivityLog
from app.repositories.mission_repository import MissionRepository

# ---------------- 단위: 단일 SELECT 보장 (DB 불필요, 어디서나 실행) ----------------


def test_sum_walking_totals_issues_single_statement() -> None:
    # torn-read 방지의 핵심: 분·걸음을 '한 번의 execute'(단일 statement snapshot)로 읽는다.
    #   누군가 두 개의 SELECT로 되돌리면 이 테스트가 깨진다(구조적 회귀 방지).
    executes: list[object] = []

    class _Row:
        def one(self) -> tuple[object, object]:
            return Decimal("30.00"), 3000

    async def fake_execute(stmt: object) -> _Row:
        executes.append(stmt)
        return _Row()

    repo = MissionRepository(cast(AsyncSession, SimpleNamespace(execute=fake_execute)))
    result = asyncio.run(repo.sum_walking_totals_today(1))

    assert result == (30.0, 3000)
    assert len(executes) == 1  # 분+걸음을 두 번이 아니라 한 문장으로 집계


# ---------------- 실 MySQL: 누적 쌍이 항상 일관되게 합산되는지 ----------------


async def _guest_user_id(db_client: AsyncClient) -> int:
    body = (await db_client.post("/api/v1/auth/guest")).json()
    return int(body["user"]["user_id"])


async def _walking_template(sm: async_sessionmaker[AsyncSession]) -> int:
    async with sm() as s:
        template = MissionTemplate(
            mission_type=MissionType.WALKING,
            title="동네 한 바퀴 걷기(테스트)",
            level=ActivityLevel.NORMAL,
            display_order=1,
            default_target_value=30,
            target_unit=TargetUnit.MINUTES,
            reward_points=10,
        )
        s.add(template)
        await s.commit()
        return int(template.mission_template_id)


async def _add_walk(
    sm: async_sessionmaker[AsyncSession], user_id: int, template_id: int, *, minutes: str, steps: int
) -> None:
    # 걷기 세션 1건 = mission_log 1 + physical_activity_log 1(오늘 KST). 서버 합산의 원천.
    async with sm() as s:
        log = MissionLog(
            user_id=user_id,
            mission_template_id=template_id,
            mission_type=MissionType.WALKING,
            status=MissionStatus.COMPLETED,
            success=True,
        )
        s.add(log)
        await s.flush()
        s.add(
            PhysicalActivityLog(
                mission_log_id=log.mission_log_id,
                activity_date=today_kst(),
                activity_type=ActivityType.WALKING,
                duration_min=Decimal(minutes),
                steps=steps,
                source=ActivitySource.SENSOR,
                sync_status=SyncStatus.SYNCED,
            )
        )
        await s.commit()


async def test_sum_walking_totals_returns_consistent_pair(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    user_id = await _guest_user_id(db_client)
    template_id = await _walking_template(db_sessionmaker)

    # 걷기 없으면 (0.0, 0)
    async with db_sessionmaker() as s:
        assert await MissionRepository(s).sum_walking_totals_today(user_id) == (0.0, 0)

    # 첫 걷기 20분/2,000보 커밋 → 쌍 (20, 2000)
    await _add_walk(db_sessionmaker, user_id, template_id, minutes="20", steps=2000)
    async with db_sessionmaker() as s:
        assert await MissionRepository(s).sum_walking_totals_today(user_id) == (20.0, 2000)

    # 두 번째 걷기 10분/1,000보 커밋 → 최신 쌍 (30, 3000). 분·걸음이 함께(같은 스냅샷) 갱신되어
    #   '이전 분 + 최신 걸음' 같은 어긋난 쌍(20/3000)이 나오지 않는다.
    await _add_walk(db_sessionmaker, user_id, template_id, minutes="10", steps=1000)
    async with db_sessionmaker() as s:
        assert await MissionRepository(s).sum_walking_totals_today(user_id) == (30.0, 3000)

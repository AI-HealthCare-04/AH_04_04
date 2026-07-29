from datetime import datetime

from httpx import AsyncClient
from sqlalchemy import event
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker

from app.models.enums import ActivityLevel, AuthProvider, MissionStatus, MissionType, OnboardingStatus, TargetUnit
from app.models.missions import MissionLog, MissionTemplate
from app.models.users import User
from app.repositories.mission_repository import MissionRepository


async def test_mission_date_filter_uses_half_open_day_boundary(
    db_sessionmaker: async_sessionmaker[AsyncSession],
) -> None:
    async with db_sessionmaker() as session:
        user = User(
            provider=AuthProvider.GUEST,
            social_id="query-boundary-user",
            nickname="QA",
            onboarding_status=OnboardingStatus.COMPLETED,
        )
        template = MissionTemplate(
            mission_type=MissionType.GAME,
            title="QA game",
            level=ActivityLevel.EASY,
            display_order=1,
            default_target_value=1,
            target_unit=TargetUnit.COUNT,
            reward_points=1,
        )
        session.add_all([user, template])
        await session.flush()
        for created_at in (
            datetime(2026, 7, 28, 23, 59, 59),
            datetime(2026, 7, 29, 0, 0, 0),
            datetime(2026, 7, 29, 23, 59, 59),
            datetime(2026, 7, 30, 0, 0, 0),
        ):
            session.add(
                MissionLog(
                    user_id=user.user_id,
                    mission_template_id=template.mission_template_id,
                    mission_type=MissionType.GAME,
                    status=MissionStatus.COMPLETED,
                    created_at=created_at,
                )
            )
        await session.commit()

        logs = await MissionRepository(session).list_mission_logs(
            user.user_id,
            on_date=datetime(2026, 7, 29).date(),
        )

    assert [log.created_at for log in logs] == [
        datetime(2026, 7, 29, 23, 59, 59),
        datetime(2026, 7, 29, 0, 0, 0),
    ]


async def test_mission_list_batches_today_meal_lookup(
    db_client: AsyncClient,
    db_sessionmaker: async_sessionmaker[AsyncSession],
    _mysql_engine: AsyncEngine,
) -> None:
    login = await db_client.post("/api/v1/auth/guest")
    auth = {"Authorization": f"Bearer {login.json()['access_token']}"}
    async with db_sessionmaker() as session:
        session.add_all(
            [
                MissionTemplate(
                    mission_type=MissionType.MEAL,
                    title=f"QA meal {index}",
                    level=ActivityLevel.EASY,
                    display_order=index,
                    default_target_value=1,
                    target_unit=TargetUnit.COUNT,
                    reward_points=1,
                )
                for index in range(10)
            ]
        )
        await session.commit()

    meal_selects = 0

    def count_meal_selects(
        conn: object,
        cursor: object,
        statement: str,
        parameters: object,
        context: object,
        executemany: bool,
    ) -> None:
        nonlocal meal_selects
        normalized = " ".join(statement.lower().split())
        if normalized.startswith("select") and " join meal_logs " in normalized:
            meal_selects += 1

    event.listen(_mysql_engine.sync_engine, "before_cursor_execute", count_meal_selects)
    try:
        response = await db_client.get("/api/v1/missions", headers=auth)
    finally:
        event.remove(_mysql_engine.sync_engine, "before_cursor_execute", count_meal_selects)

    assert response.status_code == 200
    assert len(response.json()["missions"]) == 10
    assert meal_selects == 1

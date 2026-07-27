# =====================================================================================
# 예측 대시보드(#193) walk_days/musc_days 의미 계약 회귀 테스트 (실 MySQL).
#
# 모델 학습 변수 정의와 일치하는지 원천 로그(physical_activity_logs)로 검증한다(지영 리뷰 반영):
#   - walk_days = WALKING & duration_min ≥ 30 인 '날 수'(distinct). 29분은 미포함.
#   - musc_days = SEATED/STANDING_EXERCISE 인 '날 수'. STRETCHING 등 비근력은 미포함.
#   - 같은 날 여러 건은 1일. 조회 윈도우 밖은 제외.
# =====================================================================================
from datetime import date, timedelta
from decimal import Decimal

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
from app.repositories.dashboard_repository import DashboardRepository


async def _guest_user_id(db_client: AsyncClient) -> int:
    body = (await db_client.post("/api/v1/auth/guest")).json()
    return int(body["user"]["user_id"])


async def _template(sm: async_sessionmaker[AsyncSession]) -> int:
    async with sm() as s:
        template = MissionTemplate(
            mission_type=MissionType.EXERCISE,
            title="활동(테스트)",
            level=ActivityLevel.NORMAL,
            display_order=1,
            default_target_value=1,
            target_unit=TargetUnit.MINUTES,
            reward_points=1,
        )
        s.add(template)
        await s.commit()
        return int(template.mission_template_id)


async def _add_activity(
    sm: async_sessionmaker[AsyncSession],
    user_id: int,
    template_id: int,
    *,
    activity_type: ActivityType,
    activity_date: date,
    duration_min: str | None = None,
) -> None:
    async with sm() as s:
        log = MissionLog(
            user_id=user_id,
            mission_template_id=template_id,
            mission_type=MissionType.EXERCISE,
            status=MissionStatus.COMPLETED,
            success=True,
        )
        s.add(log)
        await s.flush()
        s.add(
            PhysicalActivityLog(
                mission_log_id=log.mission_log_id,
                activity_date=activity_date,
                activity_type=activity_type,
                duration_min=Decimal(duration_min) if duration_min is not None else None,
                source=ActivitySource.SENSOR,
                sync_status=SyncStatus.SYNCED,
            )
        )
        await s.commit()


async def test_count_active_days_matches_model_variable_definition(
    db_client: AsyncClient, db_sessionmaker: async_sessionmaker[AsyncSession]
) -> None:
    user_id = await _guest_user_id(db_client)
    template_id = await _template(db_sessionmaker)
    end = today_kst()
    start = end - timedelta(days=6)  # 최근 7일

    # 활동 없으면 (0, 0)
    async with db_sessionmaker() as s:
        assert await DashboardRepository(s).count_active_days(user_id, start, end) == (0, 0)

    d0, d1, d2 = end, end - timedelta(days=1), end - timedelta(days=2)
    outside = start - timedelta(days=1)  # 윈도우 밖

    # d0: 걷기 20분+15분 = 당일 누적 35분(walk O, '하루 30분' 정의) + 근력(musc O)
    await _add_activity(db_sessionmaker, user_id, template_id, activity_type=ActivityType.WALKING, activity_date=d0, duration_min="20")
    await _add_activity(db_sessionmaker, user_id, template_id, activity_type=ActivityType.WALKING, activity_date=d0, duration_min="15")
    await _add_activity(db_sessionmaker, user_id, template_id, activity_type=ActivityType.SEATED_EXERCISE, activity_date=d0)
    # d1: 걷기 20분+9분 = 당일 누적 29분(walk X, 30분 미만) + 서서 근력(musc O)
    await _add_activity(db_sessionmaker, user_id, template_id, activity_type=ActivityType.WALKING, activity_date=d1, duration_min="20")
    await _add_activity(db_sessionmaker, user_id, template_id, activity_type=ActivityType.WALKING, activity_date=d1, duration_min="9")
    await _add_activity(db_sessionmaker, user_id, template_id, activity_type=ActivityType.STANDING_EXERCISE, activity_date=d1)
    # d2: 스트레칭(근력 아님) → 둘 다 X
    await _add_activity(db_sessionmaker, user_id, template_id, activity_type=ActivityType.STRETCHING, activity_date=d2)
    # 윈도우 밖: 40분 걷기 → 제외
    await _add_activity(db_sessionmaker, user_id, template_id, activity_type=ActivityType.WALKING, activity_date=outside, duration_min="40")

    async with db_sessionmaker() as s:
        walk_days, musc_days = await DashboardRepository(s).count_active_days(user_id, start, end)

    assert walk_days == 1  # d0 당일 누적 35분(20+15)만. d1 은 29분(20+9)이라 제외, 윈도우 밖도 제외.
    assert musc_days == 2  # d0(seated) + d1(standing). 스트레칭은 미포함.

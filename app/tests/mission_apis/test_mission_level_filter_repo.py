# =====================================================================================
# repo 수준 통합 테스트(MySQL) — 레벨 필터가 '걷기에만' 적용되는지 검증.
#
# 스펙: 걷기만 추론 모델이 정한 난이도별 변형(easy 20분/normal 30분/hard 40분)이 있고,
#   사용자에게는 자기 레벨의 걷기 '1개만' 노출된다. 운동/식사/게임은 전 레벨 공통 미션이라
#   레벨과 무관하게 노출되어야 한다. (이전 구현은 level 강일치라 easy 사용자에게
#   normal 시드인 운동/식사/게임이 전부 숨겨졌다)
# MySQL 미가용 시 conftest가 스킵한다.
# =====================================================================================
from sqlalchemy.ext.asyncio import async_sessionmaker

from app.models.enums import ActivityLevel, MissionType, TargetUnit
from app.models.missions import MissionTemplate
from app.repositories.mission_repository import MissionRepository


def _template(*, title: str, mission_type: MissionType, level: ActivityLevel) -> MissionTemplate:
    return MissionTemplate(
        mission_type=mission_type,
        title=title,
        level=level,
        display_order=1,
        default_target_value=1,
        target_unit=TargetUnit.COUNT,
        is_active=True,
    )


def _seed_all_levels() -> list[MissionTemplate]:
    """실제 시드와 같은 구성: 걷기 3레벨 + normal 공통 미션(운동/게임)."""
    return [
        _template(title="가볍게 걷기 (20분)", mission_type=MissionType.WALKING, level=ActivityLevel.EASY),
        _template(title="활기차게 걷기 (30분)", mission_type=MissionType.WALKING, level=ActivityLevel.NORMAL),
        _template(title="힘차게 걷기 (40분)", mission_type=MissionType.WALKING, level=ActivityLevel.HARD),
        _template(title="영상 따라 운동하기", mission_type=MissionType.EXERCISE, level=ActivityLevel.NORMAL),
        _template(title="게임하기", mission_type=MissionType.GAME, level=ActivityLevel.NORMAL),
    ]


async def test_level_filter_returns_single_walking_and_all_common_missions(
    db_sessionmaker: async_sessionmaker,
) -> None:
    async with db_sessionmaker() as session:
        session.add_all(_seed_all_levels())
        await session.commit()
        repo = MissionRepository(session)

        # easy 사용자: 걷기는 easy 1개만, 공통 미션(normal 시드)은 그대로 노출.
        easy = await repo.get_active_templates(level=ActivityLevel.EASY)
        easy_titles = {t.title for t in easy}
        assert easy_titles == {"가볍게 걷기 (20분)", "영상 따라 운동하기", "게임하기"}

        # 레벨이 바뀌면 그 레벨의 걷기로 '교체'되어 나온다 (하나씩만).
        hard = await repo.get_active_templates(level=ActivityLevel.HARD)
        hard_titles = {t.title for t in hard}
        assert hard_titles == {"힘차게 걷기 (40분)", "영상 따라 운동하기", "게임하기"}

        walking_count = sum(1 for t in hard if t.mission_type == MissionType.WALKING)
        assert walking_count == 1  # 걷기는 항상 정확히 1개


async def test_level_none_returns_all_templates(
    db_sessionmaker: async_sessionmaker,
) -> None:
    # level=None(무필터)은 관리/디버그 용도 그대로 전체 반환을 유지한다.
    #   (서비스 레이어가 EASY 기본값을 보장하므로 일반 사용자 경로에서는 None이 오지 않는다)
    async with db_sessionmaker() as session:
        session.add_all(_seed_all_levels())
        await session.commit()
        repo = MissionRepository(session)

        result = await repo.get_active_templates(level=None)
        assert len(result) == 5

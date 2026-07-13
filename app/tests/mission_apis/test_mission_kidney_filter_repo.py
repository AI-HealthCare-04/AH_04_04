# =====================================================================================
# repo 수준 통합 테스트(MySQL) — requires_kidney_check 딱지가 실제 DB 쿼리에서 동작하는지 검증.
#
# 0005 백필 마이그레이션은 기존 `단백질 식사 기록` 행의 requires_kidney_check를 True로 올린다.
# 그 딱지가 True인 미션이 exclude_kidney_check=True 조회에서 실제로 제외되는지(=제한 사용자에게
# 숨김/차단이 실동작하는지)를 실 DB에서 확인한다. (단위 테스트는 판정·배선만 커버)
# MySQL 미가용 시 conftest가 스킵한다.
# =====================================================================================
from sqlalchemy import update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.enums import ActivityLevel, MissionType, TargetUnit
from app.models.missions import MissionTemplate
from app.repositories.mission_repository import MissionRepository

_HIGH_PROTEIN_TITLE = "단백질 식사 기록"


def _template(*, title: str, mission_type: MissionType, requires_kidney_check: bool) -> MissionTemplate:
    return MissionTemplate(
        mission_type=mission_type,
        title=title,
        level=ActivityLevel.EASY,
        display_order=1,
        default_target_value=1,
        target_unit=TargetUnit.COUNT,
        requires_kidney_check=requires_kidney_check,
        is_active=True,
    )


async def test_kidney_flagged_template_excluded_from_active_query(db_session: AsyncSession) -> None:
    db_session.add_all(
        [
            _template(title=_HIGH_PROTEIN_TITLE, mission_type=MissionType.MEAL, requires_kidney_check=True),
            _template(title="동네 한 바퀴 걷기", mission_type=MissionType.WALKING, requires_kidney_check=False),
        ]
    )
    await db_session.commit()
    repo = MissionRepository(db_session)

    # 제한 사용자(exclude_kidney_check=True): 딱지 붙은 고단백 미션이 제외되어야 한다.
    restricted = await repo.get_active_templates(exclude_kidney_check=True)
    titles_restricted = {t.title for t in restricted}
    assert _HIGH_PROTEIN_TITLE not in titles_restricted
    assert "동네 한 바퀴 걷기" in titles_restricted

    # 허용 사용자(exclude_kidney_check=False): 둘 다 노출된다.
    allowed = await repo.get_active_templates(exclude_kidney_check=False)
    assert {t.title for t in allowed} == {_HIGH_PROTEIN_TITLE, "동네 한 바퀴 걷기"}


async def test_backfill_flips_existing_row_and_activates_filter(db_session: AsyncSession) -> None:
    # 0005가 고치려는 "이미 시드된 행"의 상태를 재현: requires_kidney_check=False로 먼저 존재.
    db_session.add(
        _template(title=_HIGH_PROTEIN_TITLE, mission_type=MissionType.MEAL, requires_kidney_check=False)
    )
    await db_session.commit()
    repo = MissionRepository(db_session)

    # 백필 전: 플래그가 False라 제한 사용자에게도 노출된다(= 안전 필터 무동작).
    before = await repo.get_active_templates(exclude_kidney_check=True)
    assert _HIGH_PROTEIN_TITLE in {t.title for t in before}

    # 0005 백필과 동일한 UPDATE를 적용(마이그레이션이 실 DB에서 하는 데이터 변경과 동일).
    await db_session.execute(
        update(MissionTemplate)
        .where(MissionTemplate.title == _HIGH_PROTEIN_TITLE)
        .values(requires_kidney_check=True)
    )
    await db_session.commit()

    # 백필 후: 제한 사용자 조회에서 제외된다(= 안전 필터 실동작).
    after = await repo.get_active_templates(exclude_kidney_check=True)
    assert _HIGH_PROTEIN_TITLE not in {t.title for t in after}

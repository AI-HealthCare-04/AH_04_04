# =====================================================================================
# seed 스크립트 회귀 테스트(MySQL) — 구버전 운동 템플릿을 참조하는 mission_logs가 있어도
# seed가 성공해야 한다. (DELETE였다면 FK 제약으로 실패해 새 '운동하기'도 시드 안 됨)
# MySQL 미가용 시 conftest가 스킵한다.
# =====================================================================================
from sqlalchemy import select
from sqlalchemy.ext.asyncio import async_sessionmaker

from app.models.enums import ActivityLevel, AuthProvider, MissionStatus, MissionType, TargetUnit
from app.models.missions import MissionLog, MissionTemplate
from app.models.users import User
from scripts.seed_mission_templates import OBSOLETE_MISSION_TITLES, seed

_OLD_TITLE = OBSOLETE_MISSION_TITLES[0]  # "앉아서 다리 펴기"


def _obsolete_template() -> MissionTemplate:
    return MissionTemplate(
        mission_type=MissionType.EXERCISE,
        title=_OLD_TITLE,
        level=ActivityLevel.NORMAL,
        display_order=20,
        default_target_value=10,
        target_unit=TargetUnit.REPS,
        is_active=True,
    )


async def test_seed_deactivates_obsolete_template_referenced_by_log(
    db_sessionmaker: async_sessionmaker,
) -> None:
    # Arrange: 구버전 운동 템플릿 + 그 템플릿을 참조하는 mission_log(사용 이력)를 만든다.
    async with db_sessionmaker() as session:
        user = User(provider=AuthProvider.GUEST, social_id="guest:seedtest", nickname="테스터")
        session.add(user)
        old = _obsolete_template()
        session.add(old)
        await session.flush()  # user_id / mission_template_id 확보
        session.add(
            MissionLog(
                user_id=user.user_id,
                mission_template_id=old.mission_template_id,
                mission_type=MissionType.EXERCISE,
                status=MissionStatus.COMPLETED,
            )
        )
        await session.commit()

    # Act: seed 실행(참조 로그가 있으므로 DELETE였다면 FK로 실패했을 상황).
    await seed(db_sessionmaker)

    # Assert: 시드가 성공해 ① 구버전 템플릿은 비활성, ② '운동하기'는 존재, ③ 이력(FK)은 보존.
    async with db_sessionmaker() as session:
        old_after = await session.scalar(
            select(MissionTemplate).where(MissionTemplate.title == _OLD_TITLE)
        )
        assert old_after is not None  # 삭제되지 않고 남아 있어야 한다
        assert old_after.is_active is False  # 목록에서 숨겨진다

        merged = await session.scalar(
            select(MissionTemplate).where(MissionTemplate.title == "영상 따라 운동하기")
        )
        assert merged is not None  # 새 통합 미션이 정상 시드됨
        assert merged.is_active is True

        log_count = len((await session.scalars(select(MissionLog))).all())
        assert log_count == 1  # 사용 이력은 보존


async def test_seed_renames_old_titles_in_place(
    db_sessionmaker: async_sessionmaker,
) -> None:
    # Arrange: 이름 통일(2026-07-16) 이전 title로 이미 시드된 DB를 재현.
    async with db_sessionmaker() as session:
        session.add(
            MissionTemplate(
                mission_type=MissionType.GAME,
                title="카드 짝 맞추기",
                description="같은 그림 카드를 찾아 짝을 맞추는 기억력 놀이예요.",
                level=ActivityLevel.NORMAL,
                display_order=40,
                default_target_value=1,
                target_unit=TargetUnit.COUNT,
                is_active=True,
            )
        )
        await session.commit()

    # Act
    await seed(db_sessionmaker)

    # Assert: ① 옛 이름 행이 '게임하기'로 제자리 UPDATE(행 신설 아님 — id 보존으로 이력 안전),
    #         ② 게임 종류 미확정에 맞춰 설명도 범용 문구로 갱신, ③ 중복 행이 생기지 않음.
    async with db_sessionmaker() as session:
        old_after = await session.scalar(
            select(MissionTemplate).where(MissionTemplate.title == "카드 짝 맞추기")
        )
        assert old_after is None  # 옛 이름은 더 이상 없다

        games = list(
            (
                await session.scalars(
                    select(MissionTemplate).where(MissionTemplate.mission_type == MissionType.GAME)
                )
            ).all()
        )
        assert len(games) == 1  # rename 이관이므로 중복 시드가 생기면 안 된다
        assert games[0].title == "게임하기"
        assert "카드" not in (games[0].description or "")  # 특정 게임명 제거

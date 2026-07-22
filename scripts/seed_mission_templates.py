"""MVP용 미션 템플릿 seed 스크립트.

migration이 아니라 별도 seed로 관리한다 (데이터는 스키마와 분리).
멱등(idempotent): 같은 title이 이미 있으면 건너뛰므로 여러 번 실행해도 중복이 안 생긴다.

실행:
    uv run --no-sync python -m scripts.seed_mission_templates
    # 또는
    uv run --no-sync python scripts/seed_mission_templates.py
"""

import asyncio

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.core.db.session import AsyncSessionLocal
from app.models.enums import (
    ActivityLevel,
    ActivityType,
    Intensity,
    MissionType,
    TargetUnit,
)
from app.models.missions import MissionTemplate

# 구버전에서 운동을 하위단계별 2개 미션으로 넣었던 흔적.
#   '운동하기' 단일 미션으로 통합하며, 이미 시드된 DB에서는 아래 title을 비활성화(is_active=False)해
#   목록에서 숨긴다. (멱등 seed는 insert만 하므로 이 정리가 없으면 옛 2개가 남아 운동 미션이 3개로 보인다.)
OBSOLETE_MISSION_TITLES = ["앉아서 다리 펴기", "서서 팔 들어올리기"]

# 이름 통일 (2026-07-16 팀 확정): 걷기는 '부사 + 걷기 (N분)' 시리즈로 성공 기준을 이름에 노출,
#   전 미션 '~하기' 형태 통일, 게임은 종류 미확정이라 범용 '게임하기'.
#   title이 멱등 키라서 이미 시드된 DB의 옛 이름은 seed()에서 새 이름으로 UPDATE 이관한다.
#   값: (새 title, 새 description|None — None이면 기존 설명 유지)
TITLE_RENAMES: dict[str, tuple[str, str | None]] = {
    "가볍게 걷기": ("가볍게 걷기 (20분)", None),
    "동네 한 바퀴 걷기": ("활기차게 걷기 (30분)", None),
    "든든하게 걷기": ("힘차게 걷기 (40분)", None),
    "운동하기": ("영상 따라 운동하기", None),
    "단백질 식사 기록": ("단백질 식사 기록하기", None),
    "카드 짝 맞추기": ("게임하기", "두뇌를 깨우는 간단한 게임을 해요."),
}


# 걷기/운동/식사/게임 최소 구성 (각 1~2개). 시니어 대상이라 난이도는 normal 기준.
MISSION_TEMPLATES: list[dict] = [
    # 걷기 — 난이도별 일일 누적 시간 목표(분): easy 20 / normal 30 / hard 40.
    #   근거: 신체활동 지침 '회당 10분 이상, 일일 30분 이상 걷기 권장'(normal 기준).
    #   달성 판정 = 당일 누적 시간(daily_total_min) ≥ 목표. 걸음수(daily_total_steps)는 표시 전용.
    {
        "mission_type": MissionType.WALKING,
        "title": "가볍게 걷기 (20분)",
        "description": "천천히 동네를 걸어요. 하루 20분을 채우면 완료예요. (여러 번 나눠 걸어도 합산돼요)",
        "level": ActivityLevel.EASY,
        "display_order": 10,
        "activity_type": ActivityType.WALKING,
        "default_target_value": 20,
        "target_unit": TargetUnit.MINUTES,
        "estimated_intensity": Intensity.LOW,
        "reward_points": 10,
    },
    {
        "mission_type": MissionType.WALKING,
        "title": "활기차게 걷기 (30분)",
        "description": "천천히 동네를 걸어요. 하루 30분을 채우면 완료예요. (여러 번 나눠 걸어도 합산돼요)",
        "level": ActivityLevel.NORMAL,
        "display_order": 10,
        "activity_type": ActivityType.WALKING,
        "default_target_value": 30,
        "target_unit": TargetUnit.MINUTES,
        "estimated_intensity": Intensity.LOW,
        "reward_points": 10,
    },
    {
        "mission_type": MissionType.WALKING,
        "title": "힘차게 걷기 (40분)",
        "description": "천천히 동네를 걸어요. 하루 40분을 채우면 완료예요. (여러 번 나눠 걸어도 합산돼요)",
        "level": ActivityLevel.HARD,
        "display_order": 10,
        "activity_type": ActivityType.WALKING,
        "default_target_value": 40,
        "target_unit": TargetUnit.MINUTES,
        "estimated_intensity": Intensity.LOW,
        "reward_points": 10,
    },
    # 운동 (안전 고지 필요)
    # 몸풀기/앉아서/서서/마무리는 '운동하기' 한 미션 안의 앱 UI 단계이고, 백엔드는 단일 미션으로 둔다.
    #   → 완료 기준: 3가지(=3회) 운동 수행(각 4분 기준 총 10분 이상). 성공 판정은 앱이 계산해 전송한다.
    #   → 세션 전체 미션이라 단일 단계 값(exercise_category/activity_type)은 두지 않는다(None).
    {
        "mission_type": MissionType.EXERCISE,
        "title": "영상 따라 운동하기",
        # 목표는 '당일 누적 10분'. 회차(3회)로 두면 중간에 끈 회차도 1회로 세어져,
        #   시작하자마자 끄기 3번으로도 성공이 된다(→ 분 기준으로 이관, 아래 seed() 참고).
        "description": "몸풀기·앉아서·서서·마무리 영상을 따라 해요. 하루 10분을 채우면 완료예요. (여러 번 나눠 해도 합산돼요)",
        "level": ActivityLevel.NORMAL,
        "display_order": 20,
        "default_target_value": 10,
        "target_unit": TargetUnit.MINUTES,
        "estimated_intensity": Intensity.LOW,
        "requires_safety_notice": True,
        "reward_points": 10,
    },
    # 식사 (1일 1회)
    {
        "mission_type": MissionType.MEAL,
        "title": "단백질 식사 기록하기",
        "description": "오늘 드신 단백질 음식(달걀·두부·생선·고기 등)을 기록해요.",
        "level": ActivityLevel.NORMAL,
        "display_order": 30,
        "default_target_value": 1,
        "target_unit": TargetUnit.COUNT,
        "daily_count_limit": 1,
        "reward_points": 5,
        # 신장질환/투석·단백질 제한 사용자에게는 노출 금지 (GET /missions 안전 필터)
        "requires_kidney_check": True,
    },
    # 게임
    {
        "mission_type": MissionType.GAME,
        "title": "게임하기",
        "description": "두뇌를 깨우는 간단한 게임을 해요.",
        "level": ActivityLevel.NORMAL,
        "display_order": 40,
        "default_target_value": 1,
        "target_unit": TargetUnit.COUNT,
        "reward_points": 5,
    },
]


async def seed(session_factory: async_sessionmaker[AsyncSession] = AsyncSessionLocal) -> None:
    # session_factory 주입 가능(기본은 앱 세션). 테스트에서 테스트 DB 세션으로 돌리기 위함.
    async with session_factory() as session:
        # 구버전 운동 미션(하위단계 분리) 정리 → '운동하기' 단일 미션으로 통합.
        #   DELETE 대신 is_active=False로 비활성화한다: 해당 템플릿을 참조하는 mission_logs가 있으면
        #   DELETE는 FK 제약으로 실패해 트랜잭션이 롤백되고 새 '운동하기'도 시드되지 않는다.
        #   비활성 템플릿은 GET 목록(get_active_templates=is_active True만)에서 숨겨지고, 이력 참조(FK)는 보존된다.
        obsolete = list(
            (
                await session.scalars(
                    select(MissionTemplate.title).where(
                        MissionTemplate.title.in_(OBSOLETE_MISSION_TITLES),
                        MissionTemplate.is_active.is_(True),
                    )
                )
            ).all()
        )
        if obsolete:
            await session.execute(
                update(MissionTemplate)
                .where(MissionTemplate.title.in_(OBSOLETE_MISSION_TITLES))
                .values(is_active=False)
            )
            print(f"  비활성화(구버전 운동 미션): {obsolete}")

        # (주의: 아래 블록은 '옛 title'을 대상으로 하므로 TITLE_RENAMES 이관보다 먼저 실행되어야 한다)
        # 걷기 목표 단위 변경 정정: 기존에 걸음수(steps) 기준으로 시드된 '동네 한 바퀴 걷기'를
        #   분(minutes) 기준 normal(30분)로 바로잡는다. title 멱등 시드는 기존 행을 건너뛰므로,
        #   이미 시드된 DB의 목표 단위/값 변경은 여기서 명시적으로 반영한다(target_unit=STEPS만 대상 → 멱등).
        await session.execute(
            update(MissionTemplate)
            .where(
                MissionTemplate.title == "동네 한 바퀴 걷기",
                MissionTemplate.target_unit == TargetUnit.STEPS,
            )
            .values(
                default_target_value=30,
                target_unit=TargetUnit.MINUTES,
                level=ActivityLevel.NORMAL,
                # description도 신규 easy/hard와 일관되게 '하루 N분' 안내로 갱신(정인 #65 리뷰).
                description="천천히 동네를 걸어요. 하루 30분을 채우면 완료예요. (여러 번 나눠 걸어도 합산돼요)",
            )
        )

        # 운동 목표 이관: 3회(count) → 10분(minutes).
        #   원래 의도는 '총 10분 이상'이었는데, 회당 4분이라 3회면 자동 충족된다고 보고 회차로 뒀다.
        #   그 전제는 '끝까지 했을 때'만 참이라, 시작하자마자 끄기를 3번 해도 성공이 됐다.
        #   서버 판정을 당일 누적 시간으로 바꾸면서(services/mission.py) 목표도 분으로 맞춘다.
        #   앱은 target_value/target_unit을 그대로 화면에 쓰므로("목표: 10 분"), 표시와 판정이 일치한다.
        #   target_unit == COUNT 가드로 이미 이관된 DB에서는 다시 실행되지 않는다(멱등).
        await session.execute(
            update(MissionTemplate)
            .where(
                MissionTemplate.title == "영상 따라 운동하기",
                MissionTemplate.target_unit == TargetUnit.COUNT,
            )
            .values(
                default_target_value=10,
                target_unit=TargetUnit.MINUTES,
                description="몸풀기·앉아서·서서·마무리 영상을 따라 해요. 하루 10분을 채우면 완료예요. (여러 번 나눠 해도 합산돼요)",
            )
        )

        # 이름 통일 이관: 옛 title → 새 title (새 이름이 이미 있으면 건너뜀 — 중복 방지 멱등 가드).
        #   mission_logs는 템플릿 id를 참조하므로 title 변경으로 이력이 깨지지 않는다.
        existing_titles = set((await session.scalars(select(MissionTemplate.title))).all())
        for old_title, (new_title, new_desc) in TITLE_RENAMES.items():
            if old_title in existing_titles and new_title not in existing_titles:
                values: dict = {"title": new_title}
                if new_desc is not None:
                    values["description"] = new_desc
                await session.execute(
                    update(MissionTemplate).where(MissionTemplate.title == old_title).values(**values)
                )
                existing_titles.discard(old_title)
                existing_titles.add(new_title)
                print(f"  이름 변경: {old_title} → {new_title}")

        inserted = 0
        for spec in MISSION_TEMPLATES:
            if spec["title"] in existing_titles:
                print(f"  건너뜀(이미 존재): {spec['title']}")
                continue
            session.add(MissionTemplate(**spec))
            inserted += 1
            print(f"  추가: [{spec['mission_type'].value}] {spec['title']}")
        await session.commit()
        print(f"\n완료: {inserted}개 추가, {len(existing_titles)}개는 기존 유지.")


if __name__ == "__main__":
    asyncio.run(seed())

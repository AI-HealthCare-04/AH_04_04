"""MVP용 미션 템플릿 seed 스크립트.

migration이 아니라 별도 seed로 관리한다 (데이터는 스키마와 분리).
멱등(idempotent): 같은 title이 이미 있으면 건너뛰므로 여러 번 실행해도 중복이 안 생긴다.

실행:
    uv run --no-sync python -m scripts.seed_mission_templates
    # 또는
    uv run --no-sync python scripts/seed_mission_templates.py
"""

import asyncio

from sqlalchemy import delete, select

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
#   '운동하기' 단일 미션으로 통합하며, 이미 시드된 DB에서는 아래 title을 제거해 정합을 맞춘다.
#   (멱등 seed는 insert만 하므로 이 정리가 없으면 옛 2개가 남아 운동 미션이 3개로 보인다.)
OBSOLETE_MISSION_TITLES = ["앉아서 다리 펴기", "서서 팔 들어올리기"]

# 걷기/운동/식사/게임 최소 구성 (각 1~2개). 시니어 대상이라 난이도는 normal 기준.
MISSION_TEMPLATES: list[dict] = [
    # 걷기
    {
        "mission_type": MissionType.WALKING,
        "title": "동네 한 바퀴 걷기",
        "description": "천천히 동네를 한 바퀴 걸어보세요. 무리하지 않는 속도면 충분해요.",
        "level": ActivityLevel.NORMAL,
        "display_order": 10,
        "activity_type": ActivityType.WALKING,
        "default_target_value": 1000,
        "target_unit": TargetUnit.STEPS,
        "estimated_intensity": Intensity.LOW,
        "reward_points": 10,
    },
    # 운동 (안전 고지 필요)
    # 몸풀기/앉아서/서서/마무리는 '운동하기' 한 미션 안의 앱 UI 단계이고, 백엔드는 단일 미션으로 둔다.
    #   → 완료 기준: 3가지(=3회) 운동 수행(각 4분 기준 총 10분 이상). 성공 판정은 앱이 계산해 전송한다.
    #   → 세션 전체 미션이라 단일 단계 값(exercise_category/activity_type)은 두지 않는다(None).
    {
        "mission_type": MissionType.EXERCISE,
        "title": "운동하기",
        "description": "몸풀기·앉아서·서서·마무리 중 3가지 운동을 해요. (각 4분, 총 10분 이상이면 완료)",
        "level": ActivityLevel.NORMAL,
        "display_order": 20,
        "default_target_value": 3,
        "target_unit": TargetUnit.COUNT,
        "estimated_intensity": Intensity.LOW,
        "requires_safety_notice": True,
        "reward_points": 10,
    },
    # 식사 (1일 1회)
    {
        "mission_type": MissionType.MEAL,
        "title": "단백질 식사 기록",
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
        "title": "카드 짝 맞추기",
        "description": "같은 그림 카드를 찾아 짝을 맞추는 기억력 놀이예요.",
        "level": ActivityLevel.NORMAL,
        "display_order": 40,
        "default_target_value": 1,
        "target_unit": TargetUnit.COUNT,
        "reward_points": 5,
    },
]


async def seed() -> None:
    async with AsyncSessionLocal() as session:
        # 구버전 운동 미션(하위단계 분리) 정리 → '운동하기' 단일 미션으로 통합.
        #   ⚠️ 해당 템플릿을 참조하는 mission_logs가 있으면 FK로 실패한다(데모 시드에는 없음 전제).
        obsolete = list(
            (
                await session.scalars(
                    select(MissionTemplate.title).where(MissionTemplate.title.in_(OBSOLETE_MISSION_TITLES))
                )
            ).all()
        )
        if obsolete:
            await session.execute(delete(MissionTemplate).where(MissionTemplate.title.in_(OBSOLETE_MISSION_TITLES)))
            print(f"  정리(구버전 운동 미션 제거): {obsolete}")

        existing_titles = set((await session.scalars(select(MissionTemplate.title))).all())
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

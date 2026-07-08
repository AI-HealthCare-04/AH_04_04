"""MVP용 미션 템플릿 seed 스크립트.

migration이 아니라 별도 seed로 관리한다 (데이터는 스키마와 분리).
멱등(idempotent): 같은 title이 이미 있으면 건너뛰므로 여러 번 실행해도 중복이 안 생긴다.

실행:
    uv run --no-sync python -m scripts.seed_mission_templates
    # 또는
    uv run --no-sync python scripts/seed_mission_templates.py
"""

import asyncio

from sqlalchemy import select

from app.core.db.session import AsyncSessionLocal
from app.models.enums import (
    ActivityLevel,
    ActivityType,
    ExerciseCategory,
    Intensity,
    MissionType,
    TargetUnit,
)
from app.models.missions import MissionTemplate

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
    {
        "mission_type": MissionType.EXERCISE,
        "title": "앉아서 다리 펴기",
        "description": "의자에 앉아 한쪽 다리를 천천히 펴고 3초 유지했다가 내려요.",
        "level": ActivityLevel.NORMAL,
        "display_order": 20,
        "exercise_category": ExerciseCategory.SEATED,
        "activity_type": ActivityType.SEATED_EXERCISE,
        "default_target_value": 10,
        "target_unit": TargetUnit.REPS,
        "estimated_intensity": Intensity.LOW,
        "requires_safety_notice": True,
        "reward_points": 10,
    },
    {
        "mission_type": MissionType.EXERCISE,
        "title": "서서 팔 들어올리기",
        "description": "바르게 서서 양팔을 어깨 높이까지 천천히 올렸다 내려요.",
        "level": ActivityLevel.NORMAL,
        "display_order": 21,
        "exercise_category": ExerciseCategory.STANDING,
        "activity_type": ActivityType.STANDING_EXERCISE,
        "default_target_value": 10,
        "target_unit": TargetUnit.REPS,
        "estimated_intensity": Intensity.MODERATE,
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

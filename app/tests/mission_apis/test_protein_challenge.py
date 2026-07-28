"""단백질 식사 기록 챌린지 — 서버 계약(검증·완료판정·upsert). 지시서 §3~4.

순수/서비스 단위 테스트: 세션·repo 는 SimpleNamespace 로 흉내 낸다(DB 없음).
"""

import asyncio
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi import HTTPException
from starlette import status

from app.core.protein_categories import PROTEIN_CATEGORY_IDS, PROTEIN_DAILY_GOAL_COUNT
from app.dtos.mission import MealDetail, MissionLogCreateRequest
from app.models.enums import MissionStatus, MissionType
from app.models.users import User
from app.services.mission import MissionService

_USER = cast(User, SimpleNamespace(user_id=1))


async def _noop(*args: object, **kwargs: object) -> None:
    return None


def _meal_template() -> SimpleNamespace:
    return SimpleNamespace(
        mission_template_id=10,
        mission_type=MissionType.MEAL,
        requires_kidney_check=False,
        reward_points=10,
    )


def _meal_request(eaten: list[str]) -> MissionLogCreateRequest:
    return MissionLogCreateRequest(
        mission_template_id=10,
        mission_type=MissionType.MEAL,
        status=MissionStatus.COMPLETED,
        success=True,  # 클라가 뭐라 보내든 서버가 다시 판정한다
        meal_detail=MealDetail(protein_foods=eaten, protein_meal_count=len(eaten)),
    )


def _service(*, existing_meal: object | None = None) -> tuple[MissionService, dict[str, object]]:
    """existing_meal 이 있으면 upsert 의 '갱신' 경로, 없으면 '생성' 경로를 탄다."""
    captured: dict[str, object] = {"created": None, "added_meal": None}
    service = MissionService(session=cast(object, SimpleNamespace(commit=_noop)))  # type: ignore[arg-type]

    async def get_template(_id: object) -> object:
        return _meal_template()

    async def latest_profile(_uid: object) -> object:
        return None

    async def find_resent(_uid: object, _tid: object, _dev: object) -> object:
        return None

    async def create_log(log: object) -> object:
        cast(SimpleNamespace, log).mission_log_id = 1
        captured["created"] = log
        return log

    async def add_meal(meal: object) -> None:
        captured["added_meal"] = meal

    async def get_today_meal(_uid: object, _tid: object) -> object:
        return existing_meal

    async def get_mission_log(_lid: object, _uid: object) -> object:
        return captured.get("existing_mlog")

    async def breakdown(_uid: object) -> dict[object, int]:
        return {}

    async def sum_points(_uid: object) -> int:
        return 0

    service.repo.get_template = get_template  # type: ignore[assignment]
    service.health_repo.get_latest_profile = latest_profile  # type: ignore[assignment]
    service.repo.find_mission_log_by_device_time = find_resent  # type: ignore[assignment]
    service.repo.create_mission_log = create_log  # type: ignore[assignment]
    service.repo.add_meal_log = add_meal  # type: ignore[assignment]
    service.repo.get_today_meal_log = get_today_meal  # type: ignore[assignment]
    service.repo.lock_user_for_completion = _noop  # type: ignore[assignment]
    service.repo.get_mission_log = get_mission_log  # type: ignore[assignment]
    service.repo.counted_breakdown_today = breakdown  # type: ignore[assignment]
    service.repo.sum_earned_points_today = sum_points  # type: ignore[assignment]
    service.repo.upsert_daily_summary = _noop  # type: ignore[assignment]
    return service, captured


def test_category_constant_has_seven_fixed_ids() -> None:
    assert PROTEIN_CATEGORY_IDS == {"meat", "fish", "egg", "soy", "dairy", "shellfish", "nuts"}
    assert PROTEIN_DAILY_GOAL_COUNT == 3


def test_undefined_category_id_is_rejected_400() -> None:
    service, _ = _service()
    with pytest.raises(HTTPException) as exc:
        asyncio.run(service.create_mission_log(_USER, _meal_request(["meat", "banana"])))
    assert exc.value.status_code == status.HTTP_400_BAD_REQUEST


def test_three_categories_completes_and_counts() -> None:
    service, cap = _service()
    resp = asyncio.run(service.create_mission_log(_USER, _meal_request(["meat", "egg", "soy"])))
    assert resp.success is True
    assert resp.counted_for_daily is True
    assert resp.earned_points == 10  # reward_points
    assert cast(SimpleNamespace, cap["created"]).success is True


def test_two_categories_saves_but_not_completed() -> None:
    service, cap = _service()
    resp = asyncio.run(service.create_mission_log(_USER, _meal_request(["meat", "egg"])))
    assert resp.success is False  # 3종 미만 → 완료 아님
    assert resp.counted_for_daily is False
    assert resp.earned_points == 0
    assert cap["created"] is not None  # 그래도 기록은 남는다


def test_duplicate_categories_counted_once() -> None:
    # 같은 카테고리를 두 번 보내도 서버가 중복 제거해 2종으로 센다(3종 미만 → 미완료).
    service, _ = _service()
    resp = asyncio.run(service.create_mission_log(_USER, _meal_request(["meat", "meat", "egg"])))
    assert resp.success is False


def test_resave_updates_existing_record_not_append() -> None:
    # 같은 날 재저장 → 기존 기록을 갱신(새 로그 생성 안 함, upsert).
    existing_meal = SimpleNamespace(
        mission_log_id=99, protein_foods=["meat"], protein_meal_count=1, counted_for_daily=False
    )
    existing_mlog = SimpleNamespace(
        status=MissionStatus.COMPLETED, success=False, counted_for_daily=False, earned_points=0, performed_at=None
    )
    service, cap = _service(existing_meal=existing_meal)
    cap["existing_mlog"] = existing_mlog

    resp = asyncio.run(service.create_mission_log(_USER, _meal_request(["meat", "egg", "soy", "dairy"])))

    assert cap["created"] is None  # 새 로그 생성 안 함(갱신)
    assert existing_meal.protein_foods == ["meat", "egg", "soy", "dairy"]  # 최신 선택으로 갱신
    assert existing_meal.protein_meal_count == 4
    assert existing_mlog.success is True  # 4종 → 완료로 갱신
    assert resp.mission_log_id == 99

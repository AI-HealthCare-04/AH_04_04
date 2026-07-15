import asyncio
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi import HTTPException, status

from app.dtos.mission import MissionLogCreateRequest
from app.models.enums import ActivityLevel, MissionStatus, MissionType
from app.models.users import User
from app.services.mission import MissionService

_USER = cast(User, SimpleNamespace(user_id=1))


def _profile(*, protein_challenge_allowed: bool) -> SimpleNamespace:
    # 필터는 서버가 계산·저장한 protein_challenge_allowed만 참조한다.
    return SimpleNamespace(protein_challenge_allowed=protein_challenge_allowed)


# ---------------- _should_hide_kidney_missions (순수 판정) ----------------


def test_hide_when_no_profile_returns_false() -> None:
    # 건강체크 전(프로필 없음)에는 과도하게 제한하지 않는다.
    assert MissionService._should_hide_kidney_missions(None) is False


@pytest.mark.parametrize(
    "allowed,expected_hide",
    [
        (True, False),  # 단백질 챌린지 허용 → 숨기지 않음
        (False, True),  # 불허(신장/단백질 제한, unknown 포함) → 숨김
    ],
)
def test_hide_follows_protein_challenge_allowed(allowed: bool, expected_hide: bool) -> None:
    profile = _profile(protein_challenge_allowed=allowed)
    assert MissionService._should_hide_kidney_missions(cast(object, profile)) is expected_hide  # type: ignore[arg-type]


# ---------------- get_missions 배선 (필터 플래그 전달) ----------------


def _service_capturing_filter(*, profile: object | None) -> tuple[MissionService, dict[str, object]]:
    service = MissionService(session=None)  # type: ignore[arg-type]
    captured: dict[str, object] = {}

    async def fake_current_level(user_id: object) -> object:
        return None

    async def fake_latest_profile(user_id: object) -> object:
        return profile

    async def fake_active_templates(
        level: object = None, mission_type: object = None, exclude_kidney_check: bool = False
    ) -> list[object]:
        captured["exclude_kidney_check"] = exclude_kidney_check
        return []

    service.repo.get_user_current_level = fake_current_level  # type: ignore[assignment]
    service.health_repo.get_latest_profile = fake_latest_profile  # type: ignore[assignment]
    service.repo.get_active_templates = fake_active_templates  # type: ignore[assignment]
    return service, captured


def test_get_missions_hides_kidney_missions_when_challenge_not_allowed() -> None:
    service, captured = _service_capturing_filter(profile=_profile(protein_challenge_allowed=False))

    asyncio.run(service.get_missions(_USER, mission_type=None, level=ActivityLevel.EASY))

    assert captured["exclude_kidney_check"] is True


def test_get_missions_keeps_kidney_missions_when_challenge_allowed() -> None:
    service, captured = _service_capturing_filter(profile=_profile(protein_challenge_allowed=True))

    asyncio.run(service.get_missions(_USER, mission_type=None, level=ActivityLevel.EASY))

    assert captured["exclude_kidney_check"] is False


def test_get_missions_keeps_all_when_no_profile() -> None:
    service, captured = _service_capturing_filter(profile=None)

    asyncio.run(service.get_missions(_USER, mission_type=None, level=ActivityLevel.EASY))

    assert captured["exclude_kidney_check"] is False


# ---------------- create_mission_log 안전 차단 (목록 필터 우회 방지) ----------------


def _meal_template(*, requires_kidney_check: bool) -> SimpleNamespace:
    return SimpleNamespace(
        mission_template_id=10,
        mission_type=MissionType.MEAL,
        requires_kidney_check=requires_kidney_check,
        reward_points=10,
    )


def _meal_request() -> MissionLogCreateRequest:
    # 식사(즉시완료) 최소 요청. success=False라 상세/집계 없이 저장 경로만 탄다.
    return MissionLogCreateRequest(
        mission_template_id=10,
        mission_type=MissionType.MEAL,
        status=MissionStatus.COMPLETED,
        success=False,
    )


def _service_for_create(*, template: object, profile: object | None) -> tuple[MissionService, dict[str, bool]]:
    service = MissionService(session=cast(object, SimpleNamespace(commit=_noop)))  # type: ignore[arg-type]
    flags = {"log_created": False}

    async def fake_get_template(template_id: object) -> object:
        return template

    async def fake_latest_profile(user_id: object) -> object:
        return profile

    async def fake_create_mission_log(log: object) -> object:
        flags["log_created"] = True
        cast(SimpleNamespace, log).mission_log_id = 1
        return log

    async def fake_count_meal(user_id: object) -> int:
        return 0

    async def fake_breakdown(user_id: object) -> dict[object, int]:
        return {}

    async def fake_sum_points(user_id: object) -> int:
        return 0

    async def fake_upsert(**kwargs: object) -> None:
        return None

    service.repo.get_template = fake_get_template  # type: ignore[assignment]
    service.health_repo.get_latest_profile = fake_latest_profile  # type: ignore[assignment]
    service.repo.create_mission_log = fake_create_mission_log  # type: ignore[assignment]
    service.repo.count_meal_missions_today = fake_count_meal  # type: ignore[assignment]
    service.repo.counted_breakdown_today = fake_breakdown  # type: ignore[assignment]
    service.repo.sum_earned_points_today = fake_sum_points  # type: ignore[assignment]
    service.repo.upsert_daily_summary = fake_upsert  # type: ignore[assignment]
    return service, flags


async def _noop() -> None:
    return None


def test_create_mission_log_blocks_restricted_user_for_kidney_mission() -> None:
    # protein_challenge_allowed=False 사용자는 목록에서 숨겨질 뿐 아니라 직접 생성도 거부되어야 한다.
    service, flags = _service_for_create(
        template=_meal_template(requires_kidney_check=True),
        profile=_profile(protein_challenge_allowed=False),
    )

    with pytest.raises(HTTPException) as exc:
        asyncio.run(service.create_mission_log(_USER, _meal_request()))

    assert exc.value.status_code == status.HTTP_403_FORBIDDEN
    assert flags["log_created"] is False  # 로그/포인트가 생성되지 않아야 한다


def test_create_mission_log_allows_kidney_mission_when_challenge_allowed() -> None:
    service, flags = _service_for_create(
        template=_meal_template(requires_kidney_check=True),
        profile=_profile(protein_challenge_allowed=True),
    )

    asyncio.run(service.create_mission_log(_USER, _meal_request()))

    assert flags["log_created"] is True


def test_create_mission_log_allows_kidney_mission_when_no_profile() -> None:
    # GET 계약과 동일하게 프로필 없음(건강체크 전)은 과도 제한하지 않는다.
    service, flags = _service_for_create(
        template=_meal_template(requires_kidney_check=True),
        profile=None,
    )

    asyncio.run(service.create_mission_log(_USER, _meal_request()))

    assert flags["log_created"] is True


def test_create_mission_log_allows_non_kidney_mission_for_restricted_user() -> None:
    # requires_kidney_check=False 미션은 제한 사용자도 정상 수행 가능해야 한다.
    service, flags = _service_for_create(
        template=_meal_template(requires_kidney_check=False),
        profile=_profile(protein_challenge_allowed=False),
    )

    asyncio.run(service.create_mission_log(_USER, _meal_request()))

    assert flags["log_created"] is True


# ---------------- get_today_walking_totals (홈 '오늘 걷기' 위젯 원천) ----------------


def test_get_today_walking_totals_delegates_to_repo() -> None:
    # 당일 누적 분·걸음을 repo(#65 합산 메서드)에서 읽어 (분, 걸음) 튜플로 돌려준다.
    calls: dict[str, int] = {}

    async def fake_minutes(user_id: int) -> float:
        calls["min_uid"] = user_id
        return 22.0

    async def fake_steps(user_id: int) -> int:
        calls["steps_uid"] = user_id
        return 2350

    service = MissionService(session=cast("object", None))  # type: ignore[arg-type]
    service.repo = cast(
        "object",
        SimpleNamespace(  # type: ignore[assignment]
            sum_walking_minutes_today=fake_minutes,
            sum_walking_steps_today=fake_steps,
        ),
    )

    total_min, total_steps = asyncio.run(service.get_today_walking_totals(_USER))

    assert (total_min, total_steps) == (22.0, 2350)
    assert calls == {"min_uid": 1, "steps_uid": 1}  # 두 합산 모두 해당 사용자로 조회

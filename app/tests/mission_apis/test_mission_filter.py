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

    async def fake_latest_profile(user_id: object) -> object:
        return profile

    async def fake_active_templates(
        level: object = None, mission_type: object = None, exclude_kidney_check: bool = False
    ) -> list[object]:
        captured["exclude_kidney_check"] = exclude_kidney_check
        captured["level"] = level
        return []

    service.health_repo.get_latest_profile = fake_latest_profile  # type: ignore[assignment]
    service.repo.get_active_templates = fake_active_templates  # type: ignore[assignment]
    return service, captured


def test_get_missions_hides_kidney_missions_when_challenge_not_allowed() -> None:
    service, captured = _service_capturing_filter(profile=_profile(protein_challenge_allowed=False))

    asyncio.run(service.get_missions(_USER, mission_type=None))

    assert captured["exclude_kidney_check"] is True


def test_get_missions_keeps_kidney_missions_when_challenge_allowed() -> None:
    service, captured = _service_capturing_filter(profile=_profile(protein_challenge_allowed=True))

    asyncio.run(service.get_missions(_USER, mission_type=None))

    assert captured["exclude_kidney_check"] is False


def test_get_missions_keeps_all_when_no_profile() -> None:
    service, captured = _service_capturing_filter(profile=None)

    asyncio.run(service.get_missions(_USER, mission_type=None))

    assert captured["exclude_kidney_check"] is False


# ---------------- get_missions 걷기 고정 필터 (난이도 폐기 #428) ----------------
#   걷기는 일일 20분 단일 목표 — 사용자별 저장 레벨 조회 자체가 없어졌고,
#   목록은 무조건 EASY(20분) 걷기 필터로 조회한다. (레벨 필터가 걷기에만 적용되고
#   다른 종류는 영향을 받지 않는 것은 test_mission_level_filter_repo 가 잠근다.)


def test_get_missions_always_filters_walking_to_easy() -> None:
    # 과거에 다른 레벨을 저장했던 사용자든 처음인 사용자든, 서비스는 사용자 레벨을
    # 조회하지 않고 항상 EASY 필터로 목록을 만든다.
    service, captured = _service_capturing_filter(profile=None)

    asyncio.run(service.get_missions(_USER, mission_type=None))

    assert captured["level"] is ActivityLevel.EASY


# ---------------- get_missions 오늘 진행(today_progress) 부착 ----------------
#   운동·걷기 카드가 재생/측정 전에도 '오늘까지 N분'을 보이도록, 목록에 서버 당일 누적을 붙인다.
#   누적은 사용자·종류 단위 권위값이므로 종류당 한 번만 집계하고, 그 종류가 목록에 없으면 조회하지 않는다.


def _template(mission_type: MissionType, *, template_id: int, target_value: int) -> SimpleNamespace:
    return SimpleNamespace(
        mission_template_id=template_id,
        mission_type=mission_type,
        title="미션",
        description=None,
        level="easy",
        default_target_value=target_value,
        target_unit="min",
        requires_safety_notice=False,
        daily_count_limit=None,
        reward_points=10,
        requires_kidney_check=False,
    )


def _service_with_templates(
    templates: list[object],
    *,
    exercise_min: float = 0.0,
    walking: tuple[float, int] = (0.0, 0),
    game_done: bool = False,
) -> tuple[MissionService, dict[str, int]]:
    service = MissionService(session=None)  # type: ignore[arg-type]
    calls = {"exercise": 0, "walking": 0, "game": 0}

    async def fake_latest_profile(user_id: object) -> object:
        return None  # 프로필 없음 → 신장 필터 미적용

    async def fake_active_templates(
        level: object = None, mission_type: object = None, exclude_kidney_check: bool = False
    ) -> list[object]:
        return templates

    async def fake_exercise_min(user_id: object) -> float:
        calls["exercise"] += 1
        return exercise_min

    async def fake_walking(user_id: object) -> tuple[float, int]:
        calls["walking"] += 1
        return walking

    async def fake_meal_logs(user_id: object, template_ids: object) -> dict[object, object]:
        return {}  # 식사 배치 조회 — 운동·걷기 테스트에선 오늘 기록 없음(빈 dict)

    async def fake_has_counted_today(user_id: object, mission_type: object) -> bool:
        calls["game"] += 1
        return game_done

    service.repo.has_counted_today = fake_has_counted_today  # type: ignore[assignment]
    service.health_repo.get_latest_profile = fake_latest_profile  # type: ignore[assignment]
    service.repo.get_active_templates = fake_active_templates  # type: ignore[assignment]
    service.repo.get_today_meal_logs = fake_meal_logs  # type: ignore[assignment]
    service.repo.sum_exercise_minutes_today = fake_exercise_min  # type: ignore[assignment]
    service.repo.sum_walking_totals_today = fake_walking  # type: ignore[assignment]
    return service, calls


def test_get_missions_attaches_exercise_today_progress_below_goal() -> None:
    service, calls = _service_with_templates(
        [_template(MissionType.EXERCISE, template_id=1, target_value=10)],
        exercise_min=4.0,
    )

    resp = asyncio.run(service.get_missions(_USER, mission_type=None))

    assert calls == {"exercise": 1, "walking": 0, "game": 0}  # 운동만 있으니 걷기·게임 집계는 안 탄다
    progress = resp[0].today_progress
    assert progress is not None
    assert progress.total_min == 4.0
    assert progress.total_steps is None  # 운동은 걸음 없음
    assert progress.goal_reached is False


def test_get_missions_exercise_goal_reached_at_target() -> None:
    service, _ = _service_with_templates(
        [_template(MissionType.EXERCISE, template_id=1, target_value=10)],
        exercise_min=10.0,
    )

    resp = asyncio.run(service.get_missions(_USER, mission_type=None))

    assert resp[0].today_progress is not None
    assert resp[0].today_progress.goal_reached is True  # 목표(10)에 도달하면 달성


def test_get_missions_attaches_walking_today_progress_with_steps() -> None:
    service, calls = _service_with_templates(
        [_template(MissionType.WALKING, template_id=2, target_value=20)],
        walking=(12.0, 1500),
    )

    resp = asyncio.run(service.get_missions(_USER, mission_type=None))

    assert calls == {"exercise": 0, "walking": 1, "game": 0}
    progress = resp[0].today_progress
    assert progress is not None
    assert progress.total_min == 12.0
    assert progress.total_steps == 1500  # 걷기는 걸음 누적도 표시
    assert progress.goal_reached is False


def test_get_missions_attaches_game_today_done() -> None:
    # 게임(1회성, #346): 오늘 counted 완료가 있으면 today_done=True — 카드가 '오늘 했음' 배지를 그린다.
    service, calls = _service_with_templates(
        [_template(MissionType.GAME, template_id=4, target_value=1)],
        game_done=True,
    )

    resp = asyncio.run(service.get_missions(_USER, mission_type=None))

    assert calls == {"exercise": 0, "walking": 0, "game": 1}
    assert resp[0].today_done is True
    assert resp[0].today_progress is None  # 1회성이라 진행바용 progress 는 없다


def test_get_missions_game_today_done_false_when_not_played() -> None:
    service, _ = _service_with_templates(
        [_template(MissionType.GAME, template_id=4, target_value=1)],
        game_done=False,
    )

    resp = asyncio.run(service.get_missions(_USER, mission_type=None))

    assert resp[0].today_done is False  # null 이 아니라 False — 앱이 '아직 안 함'을 구분


def test_get_missions_today_done_null_for_non_game_types() -> None:
    # today_done 은 게임 한정 — 다른 종류엔 붙지 않고, 게임이 목록에 없으면 조회도 안 탄다.
    service, calls = _service_with_templates(
        [_template(MissionType.EXERCISE, template_id=1, target_value=10)],
        game_done=True,
    )

    resp = asyncio.run(service.get_missions(_USER, mission_type=None))

    assert calls["game"] == 0
    assert resp[0].today_done is None


def test_get_missions_skips_progress_queries_when_type_absent() -> None:
    # 운동·걷기가 목록에 없으면(식사만) 누적 집계 SELECT 를 아예 타지 않는다.
    meal = _template(MissionType.MEAL, template_id=3, target_value=1)

    service, calls = _service_with_templates([meal])  # get_today_meal_logs 는 헬퍼가 빈 dict 로 stub

    resp = asyncio.run(service.get_missions(_USER, mission_type=None))

    assert calls == {"exercise": 0, "walking": 0, "game": 0}
    assert resp[0].today_progress is None  # 식사 미션엔 today_progress 안 붙는다


def test_get_missions_progress_zero_when_nothing_done_today() -> None:
    # 오늘 아직 안 했어도 today_progress 는 null 이 아니라 0 으로 내려간다(앱이 '오늘까지 0분'을 판단할 수 있게).
    service, _ = _service_with_templates(
        [_template(MissionType.EXERCISE, template_id=1, target_value=10)],
        exercise_min=0.0,
    )

    resp = asyncio.run(service.get_missions(_USER, mission_type=None))

    assert resp[0].today_progress is not None
    assert resp[0].today_progress.total_min == 0.0
    assert resp[0].today_progress.goal_reached is False


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

    async def fake_get_today_meal(user_id: object, template_id: object) -> object:
        return None  # 오늘 기록 없음 → upsert 의 '생성' 경로

    async def fake_add_meal(meal_log: object) -> None:
        return None

    service.repo.get_template = fake_get_template  # type: ignore[assignment]
    service.health_repo.get_latest_profile = fake_latest_profile  # type: ignore[assignment]
    service.repo.create_mission_log = fake_create_mission_log  # type: ignore[assignment]
    service.repo.get_today_meal_log = fake_get_today_meal  # type: ignore[assignment]
    # 당일 upsert 직렬화 잠금 — DB 없는 단위테스트에선 no-op
    service.repo.lock_user_for_completion = _noop  # type: ignore[method-assign]
    service.repo.add_meal_log = fake_add_meal  # type: ignore[assignment]
    service.repo.count_meal_missions_today = fake_count_meal  # type: ignore[assignment]
    service.repo.counted_breakdown_today = fake_breakdown  # type: ignore[assignment]
    service.repo.sum_earned_points_today = fake_sum_points  # type: ignore[assignment]
    service.repo.upsert_daily_summary = fake_upsert  # type: ignore[assignment]
    return service, flags


async def _noop(*args: object, **kwargs: object) -> None:
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


def test_get_today_walking_totals_reads_pair_in_single_query() -> None:
    # 당일 누적 (분, 걸음)을 repo의 '단일 SELECT' 메서드로 한 번에 읽어 그대로 돌려준다.
    #   분·걸음을 각각 조회하지 않는다(torn-read 방지 — 지영 리뷰 #69).
    calls: dict[str, int] = {}

    async def fake_totals(user_id: int) -> tuple[float, int]:
        calls["uid"] = user_id
        return 22.0, 2350

    service = MissionService(session=cast("object", None))  # type: ignore[arg-type]
    service.repo = cast("object", SimpleNamespace(sum_walking_totals_today=fake_totals))  # type: ignore[assignment]

    total_min, total_steps = asyncio.run(service.get_today_walking_totals(_USER))

    assert (total_min, total_steps) == (22.0, 2350)
    assert calls == {"uid": 1}  # 분·걸음을 한 번의(단일 스냅샷) 조회로만 가져온다

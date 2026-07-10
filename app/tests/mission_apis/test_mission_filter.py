import asyncio
from types import SimpleNamespace
from typing import cast

import pytest

from app.models.enums import ActivityLevel
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

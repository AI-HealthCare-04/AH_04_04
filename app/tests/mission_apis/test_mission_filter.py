import asyncio
from types import SimpleNamespace
from typing import cast

import pytest

from app.models.enums import ActivityLevel, KidneyStatus, ProteinRestrictionStatus
from app.models.users import User
from app.services.mission import MissionService

_USER = cast(User, SimpleNamespace(user_id=1))


def _profile(*, kidney: KidneyStatus, protein: ProteinRestrictionStatus) -> SimpleNamespace:
    return SimpleNamespace(kidney_status=kidney, protein_restriction_status=protein)


# ---------------- _should_hide_kidney_missions (순수 판정) ----------------


def test_hide_when_no_profile_returns_false() -> None:
    # 건강체크 전(프로필 없음)에는 과도하게 제한하지 않는다.
    assert MissionService._should_hide_kidney_missions(None) is False


@pytest.mark.parametrize(
    "kidney,protein,expected",
    [
        (KidneyStatus.NONE, ProteinRestrictionStatus.NONE, False),
        (KidneyStatus.UNKNOWN, ProteinRestrictionStatus.UNKNOWN, False),  # unknown은 차단 안 함
        (KidneyStatus.KIDNEY_DISEASE, ProteinRestrictionStatus.NONE, True),
        (KidneyStatus.DIALYSIS, ProteinRestrictionStatus.NONE, True),
        (KidneyStatus.NONE, ProteinRestrictionStatus.RESTRICTED, True),
    ],
)
def test_hide_by_kidney_or_protein_status(
    kidney: KidneyStatus, protein: ProteinRestrictionStatus, expected: bool
) -> None:
    profile = _profile(kidney=kidney, protein=protein)
    assert MissionService._should_hide_kidney_missions(cast(object, profile)) is expected  # type: ignore[arg-type]


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


def test_get_missions_hides_kidney_missions_for_restricted_user() -> None:
    profile = _profile(kidney=KidneyStatus.DIALYSIS, protein=ProteinRestrictionStatus.NONE)
    service, captured = _service_capturing_filter(profile=profile)

    asyncio.run(service.get_missions(_USER, mission_type=None, level=ActivityLevel.EASY))

    assert captured["exclude_kidney_check"] is True


def test_get_missions_keeps_kidney_missions_for_unrestricted_user() -> None:
    profile = _profile(kidney=KidneyStatus.NONE, protein=ProteinRestrictionStatus.NONE)
    service, captured = _service_capturing_filter(profile=profile)

    asyncio.run(service.get_missions(_USER, mission_type=None, level=ActivityLevel.EASY))

    assert captured["exclude_kidney_check"] is False

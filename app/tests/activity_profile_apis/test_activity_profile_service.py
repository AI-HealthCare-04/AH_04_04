from datetime import datetime
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.activity_profile import ActivityProfileUpdateRequest
from app.models.activity import UserActivityProfile
from app.models.enums import ActivityLevel, LevelReason
from app.models.users import User
from app.services.activity_profile import ActivityProfileService

_NOW = datetime(2026, 7, 9, 12, 0, 0)
_USER = cast(User, SimpleNamespace(user_id=1))


class _FakeSession:
    def __init__(self) -> None:
        self.committed = False
        self.refreshed: object | None = None

    async def commit(self) -> None:
        self.committed = True

    async def refresh(self, instance: object) -> None:
        self.refreshed = instance
        if isinstance(instance, UserActivityProfile):
            if instance.activity_profile_id is None:
                instance.activity_profile_id = 100
            if instance.updated_at is None:
                instance.updated_at = _NOW


class _FakeActivityProfileRepository:
    def __init__(self, profile: UserActivityProfile | None = None) -> None:
        self.profile = profile
        self.created_profile: UserActivityProfile | None = None

    async def get_by_user_id(self, user_id: int) -> UserActivityProfile | None:
        return self.profile

    async def create_profile(self, profile: UserActivityProfile) -> UserActivityProfile:
        profile.activity_profile_id = 100
        profile.updated_at = _NOW
        self.profile = profile
        self.created_profile = profile
        return profile

    async def update_profile(self, profile: UserActivityProfile) -> UserActivityProfile:
        profile.updated_at = _NOW
        self.profile = profile
        return profile


def _service(
    profile: UserActivityProfile | None = None,
) -> tuple[ActivityProfileService, _FakeActivityProfileRepository, _FakeSession]:
    session = _FakeSession()
    repo = _FakeActivityProfileRepository(profile)
    service = ActivityProfileService(cast(AsyncSession, session))
    service.repo = repo  # type: ignore[assignment]
    return service, repo, session


def _profile() -> UserActivityProfile:
    return UserActivityProfile(
        activity_profile_id=10,
        user_id=1,
        current_level=ActivityLevel.EASY,
        level_reason=LevelReason.DEFAULT,
        physical_assessment_id=None,
        started_at=_NOW,
        updated_at=_NOW,
    )


async def test_get_profile_creates_default_profile_when_missing() -> None:
    service, repo, session = _service()

    result = await service.get_profile(_USER)

    assert result.activity_profile_id == 100
    assert result.current_level == ActivityLevel.EASY
    assert result.level_reason == LevelReason.DEFAULT
    assert repo.created_profile is not None
    assert session.committed is True


async def test_update_profile_changes_existing_profile() -> None:
    service, repo, session = _service(_profile())

    result = await service.update_profile(
        _USER,
        ActivityProfileUpdateRequest(to_level=ActivityLevel.HARD, reason_type=LevelReason.USER_SELECTED),
    )

    assert result.activity_profile_id == 10
    assert result.current_level == ActivityLevel.HARD
    assert result.level_reason == LevelReason.USER_SELECTED
    assert repo.profile is not None
    assert repo.profile.current_level == ActivityLevel.HARD
    assert session.committed is True


async def test_update_profile_creates_profile_when_missing() -> None:
    service, repo, session = _service()

    result = await service.update_profile(
        _USER,
        ActivityProfileUpdateRequest(to_level=ActivityLevel.NORMAL, reason_type=LevelReason.USER_SELECTED),
    )

    assert result.activity_profile_id == 100
    assert result.current_level == ActivityLevel.NORMAL
    assert result.level_reason == LevelReason.USER_SELECTED
    assert repo.created_profile is not None
    assert session.committed is True


async def test_update_profile_requires_user_acceptance() -> None:
    service, _, session = _service(_profile())

    with pytest.raises(HTTPException) as exc:
        await service.update_profile(
            _USER,
            ActivityProfileUpdateRequest(
                to_level=ActivityLevel.NORMAL,
                reason_type=LevelReason.USER_SELECTED,
                accepted_by_user=False,
            ),
        )

    assert exc.value.status_code == 400
    assert session.committed is False


def test_update_request_rejects_invalid_level() -> None:
    with pytest.raises(ValidationError):
        ActivityProfileUpdateRequest.model_validate(
            {"to_level": "huge", "reason_type": "user_selected", "accepted_by_user": True}
        )

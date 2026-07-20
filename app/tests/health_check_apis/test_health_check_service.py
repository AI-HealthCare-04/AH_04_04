from datetime import datetime
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.health_check import HealthCheckSessionCreateRequest
from app.models.activity import UserActivityProfile
from app.models.enums import ActivityLevel, HealthCheckStatus, InputMethod, LevelReason, OnboardingStatus
from app.models.health import HealthCheckSession
from app.models.users import User
from app.services.health_check import HealthCheckService

_NOW = datetime(2026, 7, 10, 12, 0, 0)


class _FakeSession:
    def __init__(self) -> None:
        self.committed = False
        self.refreshed: list[object] = []

    async def commit(self) -> None:
        self.committed = True

    async def refresh(self, instance: object) -> None:
        self.refreshed.append(instance)
        if isinstance(instance, HealthCheckSession):
            if instance.session_id is None:
                instance.session_id = 100
            if instance.created_at is None:
                instance.created_at = _NOW
        if isinstance(instance, UserActivityProfile):
            if instance.activity_profile_id is None:
                instance.activity_profile_id = 200
            if instance.updated_at is None:
                instance.updated_at = _NOW


class _FakeHealthCheckRepository:
    def __init__(self, health_check_session: HealthCheckSession | None = None) -> None:
        self.health_check_session = health_check_session
        self.created_session: HealthCheckSession | None = None

    async def create_session(self, health_check_session: HealthCheckSession) -> HealthCheckSession:
        health_check_session.session_id = 100
        health_check_session.created_at = _NOW
        self.health_check_session = health_check_session
        self.created_session = health_check_session
        return health_check_session

    async def get_session(self, session_id: int, user_id: int) -> HealthCheckSession | None:
        return self.health_check_session

    async def update_session(self, health_check_session: HealthCheckSession) -> HealthCheckSession:
        self.health_check_session = health_check_session
        return health_check_session


class _FakeActivityProfileRepository:
    def __init__(self, activity_profile: UserActivityProfile | None = None) -> None:
        self.activity_profile = activity_profile
        self.created_profile: UserActivityProfile | None = None

    async def get_by_user_id(self, user_id: int) -> UserActivityProfile | None:
        return self.activity_profile

    async def create_profile(self, profile: UserActivityProfile) -> UserActivityProfile:
        profile.activity_profile_id = 200
        profile.updated_at = _NOW
        self.activity_profile = profile
        self.created_profile = profile
        return profile


def _user() -> User:
    return cast(User, SimpleNamespace(user_id=1, onboarding_status=OnboardingStatus.PENDING))


def _service(
    health_check_session: HealthCheckSession | None = None,
    activity_profile: UserActivityProfile | None = None,
) -> tuple[HealthCheckService, _FakeHealthCheckRepository, _FakeActivityProfileRepository, _FakeSession]:
    session = _FakeSession()
    repo = _FakeHealthCheckRepository(health_check_session)
    activity_repo = _FakeActivityProfileRepository(activity_profile)
    service = HealthCheckService(cast(AsyncSession, session))
    service.repo = repo  # type: ignore[assignment]
    service.activity_repo = activity_repo  # type: ignore[assignment]
    return service, repo, activity_repo, session


def _started_session() -> HealthCheckSession:
    return HealthCheckSession(
        session_id=10,
        user_id=1,
        status=HealthCheckStatus.STARTED,
        input_method=InputMethod.FORM,
        has_estimated_value=False,
        created_at=_NOW,
        completed_at=None,
    )


def _activity_profile() -> UserActivityProfile:
    return UserActivityProfile(
        activity_profile_id=201,
        user_id=1,
        current_level=ActivityLevel.NORMAL,
        level_reason=LevelReason.USER_SELECTED,
        physical_assessment_id=None,
        started_at=_NOW,
        updated_at=_NOW,
    )


async def test_start_session_creates_started_form_session() -> None:
    service, repo, _, session = _service()

    result = await service.start_session(_user(), HealthCheckSessionCreateRequest())

    assert result.session_id == 100
    assert result.status == HealthCheckStatus.STARTED
    assert result.input_method == InputMethod.FORM
    assert result.has_estimated_value is False
    assert repo.created_session is not None
    assert session.committed is True


async def test_skip_session_marks_started_session_skipped_and_completes_onboarding() -> None:
    service, repo, activity_repo, session = _service(_started_session())
    user = _user()

    result = await service.skip_session(user, 10)

    assert result.session_id == 10
    assert result.status == HealthCheckStatus.SKIPPED
    assert result.onboarding_status == OnboardingStatus.COMPLETED
    assert result.activity_profile.current_level == ActivityLevel.EASY
    assert result.activity_profile.level_reason == LevelReason.RULE
    assert user.onboarding_status == OnboardingStatus.COMPLETED
    assert repo.health_check_session is not None
    assert repo.health_check_session.status == HealthCheckStatus.SKIPPED
    assert repo.health_check_session.completed_at is not None
    assert activity_repo.created_profile is not None
    assert session.committed is True


async def test_skip_session_reuses_existing_activity_profile() -> None:
    existing_profile = _activity_profile()
    service, _, activity_repo, _ = _service(_started_session(), existing_profile)

    result = await service.skip_session(_user(), 10)

    assert result.activity_profile.activity_profile_id == existing_profile.activity_profile_id
    assert result.activity_profile.current_level == ActivityLevel.NORMAL
    assert result.activity_profile.level_reason == LevelReason.USER_SELECTED
    assert activity_repo.created_profile is None


async def test_missing_session_returns_404() -> None:
    service, _, _, session = _service()

    with pytest.raises(HTTPException) as exc:
        await service.skip_session(_user(), 999)

    assert exc.value.status_code == 404
    assert exc.value.detail == "세션을 찾을 수 없습니다."
    assert session.committed is False


async def test_finished_session_cannot_be_changed_again() -> None:
    # 이미 종료된 세션에 skip을 재시도하면 409(_get_started_session 가드).
    finished = _started_session()
    finished.status = HealthCheckStatus.SKIPPED
    service, _, _, session = _service(finished)

    with pytest.raises(HTTPException) as exc:
        await service.skip_session(_user(), 10)

    assert exc.value.status_code == 409
    assert exc.value.detail == "이미 종료된 세션입니다."
    assert session.committed is False


def test_create_request_rejects_invalid_input_method() -> None:
    with pytest.raises(ValidationError):
        HealthCheckSessionCreateRequest.model_validate({"input_method": "chat"})

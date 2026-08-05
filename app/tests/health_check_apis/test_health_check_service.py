from datetime import datetime
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.health_check import HealthCheckSessionCreateRequest
from app.models.enums import HealthCheckStatus, InputMethod, OnboardingStatus
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


class _FakeHealthCheckRepository:
    def __init__(self, health_check_session: HealthCheckSession | None = None) -> None:
        self.health_check_session = health_check_session
        self.created_session: HealthCheckSession | None = None
        # 중단 후 재진입 재사용(#180)용. 기본 None = 남은 STARTED 없음 → start_session 이 새로 만든다.
        self.lingering_started: HealthCheckSession | None = None

    async def get_latest_started(self, user_id: int) -> HealthCheckSession | None:
        return self.lingering_started

    async def create_session(self, health_check_session: HealthCheckSession) -> HealthCheckSession:
        health_check_session.session_id = 100
        health_check_session.created_at = _NOW
        self.health_check_session = health_check_session
        self.created_session = health_check_session
        return health_check_session

    async def get_session(
        self, session_id: int, user_id: int, *, for_update: bool = False
    ) -> HealthCheckSession | None:
        return self.health_check_session

    async def update_session(self, health_check_session: HealthCheckSession) -> HealthCheckSession:
        self.health_check_session = health_check_session
        return health_check_session


def _user() -> User:
    return cast(User, SimpleNamespace(user_id=1, onboarding_status=OnboardingStatus.PENDING))


def _service(
    health_check_session: HealthCheckSession | None = None,
) -> tuple[HealthCheckService, _FakeHealthCheckRepository, _FakeSession]:
    session = _FakeSession()
    repo = _FakeHealthCheckRepository(health_check_session)
    service = HealthCheckService(cast(AsyncSession, session))
    service.repo = repo  # type: ignore[assignment]
    return service, repo, session


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


async def test_start_session_creates_started_form_session() -> None:
    service, repo, session = _service()

    result = await service.start_session(_user(), HealthCheckSessionCreateRequest())

    assert result.session_id == 100
    assert result.status == HealthCheckStatus.STARTED
    assert result.input_method == InputMethod.FORM
    assert result.has_estimated_value is False
    assert repo.created_session is not None
    assert session.committed is True


async def test_skip_session_marks_started_session_skipped_and_completes_onboarding() -> None:
    # 난이도 폐기(#428): 건너뛰기는 기본 난이도를 만들지 않고 온보딩 상태 전이만 담당한다.
    service, repo, session = _service(_started_session())
    user = _user()

    result = await service.skip_session(user, 10)

    assert result.session_id == 10
    assert result.status == HealthCheckStatus.SKIPPED
    assert result.onboarding_status == OnboardingStatus.COMPLETED.value
    assert user.onboarding_status == OnboardingStatus.COMPLETED
    assert repo.health_check_session is not None
    assert repo.health_check_session.status == HealthCheckStatus.SKIPPED
    assert repo.health_check_session.completed_at is not None
    assert session.committed is True


async def test_missing_session_returns_404() -> None:
    service, _, session = _service()

    with pytest.raises(HTTPException) as exc:
        await service.skip_session(_user(), 999)

    assert exc.value.status_code == 404
    assert exc.value.detail == "세션을 찾을 수 없습니다."
    assert session.committed is False


async def test_finished_session_cannot_be_changed_again() -> None:
    # 이미 종료된 세션에 skip을 재시도하면 409(_get_started_session 가드).
    finished = _started_session()
    finished.status = HealthCheckStatus.SKIPPED
    service, _, session = _service(finished)

    with pytest.raises(HTTPException) as exc:
        await service.skip_session(_user(), 10)

    assert exc.value.status_code == 409
    assert exc.value.detail == "이미 종료된 세션입니다."
    assert session.committed is False


def test_create_request_rejects_invalid_input_method() -> None:
    with pytest.raises(ValidationError):
        HealthCheckSessionCreateRequest.model_validate({"input_method": "chat"})

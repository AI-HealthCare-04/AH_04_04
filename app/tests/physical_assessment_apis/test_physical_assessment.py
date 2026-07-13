from datetime import datetime
from decimal import Decimal
from types import SimpleNamespace
from typing import cast

import pytest
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.physical_assessment import PhysicalAssessmentCreateRequest
from app.models.activity import ActivityLevelChangeLog, UserActivityProfile
from app.models.enums import ActivityLevel, LevelReason, ReasonType
from app.models.health import PhysicalAssessment
from app.models.users import User
from app.services.physical_assessment import PhysicalAssessmentService


class _FakeSession:
    def __init__(self) -> None:
        self.committed = False

    async def commit(self) -> None:
        self.committed = True

    async def refresh(self, instance: object) -> None:
        return None


class _FakePhysicalAssessmentRepository:
    def __init__(self) -> None:
        self.created: PhysicalAssessment | None = None

    async def create_physical_assessment(self, assessment: PhysicalAssessment) -> PhysicalAssessment:
        assessment.physical_assessment_id = 30
        self.created = assessment
        return assessment


class _FakeActivityProfileRepository:
    def __init__(self, profile: UserActivityProfile | None = None) -> None:
        self.profile = profile
        self.created: UserActivityProfile | None = None
        self.updated: UserActivityProfile | None = None
        self.created_level_change_log: ActivityLevelChangeLog | None = None

    async def get_by_user_id(self, user_id: int) -> UserActivityProfile | None:
        return self.profile

    async def create_profile(self, profile: UserActivityProfile) -> UserActivityProfile:
        profile.activity_profile_id = 100
        self.profile = profile
        self.created = profile
        return profile

    async def update_profile(self, profile: UserActivityProfile) -> UserActivityProfile:
        self.profile = profile
        self.updated = profile
        return profile

    async def create_level_change_log(self, log: ActivityLevelChangeLog) -> ActivityLevelChangeLog:
        self.created_level_change_log = log
        return log


def _service(
    profile: UserActivityProfile | None = None,
) -> tuple[
    PhysicalAssessmentService,
    _FakePhysicalAssessmentRepository,
    _FakeActivityProfileRepository,
    _FakeSession,
]:
    session = _FakeSession()
    service = PhysicalAssessmentService(cast(AsyncSession, session))
    assessment_repo = _FakePhysicalAssessmentRepository()
    activity_repo = _FakeActivityProfileRepository(profile)
    service.repo = assessment_repo  # type: ignore[assignment]
    service.activity_repo = activity_repo  # type: ignore[assignment]
    return service, assessment_repo, activity_repo, session


def test_physical_assessment_requires_chair_stand_time_when_not_skipped() -> None:
    with pytest.raises(ValidationError):
        PhysicalAssessmentCreateRequest(walk_6m_time_sec=Decimal("7.2"))


def test_physical_assessment_requires_walk_time_when_not_skipped() -> None:
    with pytest.raises(ValidationError):
        PhysicalAssessmentCreateRequest(chair_stand_5_time_sec=Decimal("12.3"))


def test_physical_assessment_allows_skipped_measurements() -> None:
    data = PhysicalAssessmentCreateRequest(chair_stand_skipped=True, walk_6m_skipped=True)

    assert data.chair_stand_5_time_sec is None
    assert data.walk_6m_time_sec is None


def test_walk_speed_uses_two_decimal_places() -> None:
    speed = PhysicalAssessmentService._calculate_walk_speed(Decimal("6.00"), Decimal("7.00"))

    assert speed == Decimal("0.86")


def test_activity_level_uses_walk_speed_thresholds() -> None:
    assert (
        PhysicalAssessmentService._determine_activity_level(
            walk_speed=Decimal("0.79"),
            pain_reported=False,
            dizziness_reported=False,
        )
        == ActivityLevel.EASY
    )
    assert (
        PhysicalAssessmentService._determine_activity_level(
            walk_speed=Decimal("0.92"),
            pain_reported=False,
            dizziness_reported=False,
        )
        == ActivityLevel.NORMAL
    )
    assert (
        PhysicalAssessmentService._determine_activity_level(
            walk_speed=Decimal("1.00"),
            pain_reported=False,
            dizziness_reported=False,
        )
        == ActivityLevel.HARD
    )


def test_activity_level_falls_back_to_easy_for_safety_flags() -> None:
    assert (
        PhysicalAssessmentService._determine_activity_level(
            walk_speed=Decimal("1.20"),
            pain_reported=True,
            dizziness_reported=False,
        )
        == ActivityLevel.EASY
    )


async def test_create_assessment_sets_activity_profile_from_walk_speed() -> None:
    service, assessment_repo, activity_repo, session = _service()
    user = cast(User, SimpleNamespace(user_id=1))

    response = await service.create_assessment(
        user,
        PhysicalAssessmentCreateRequest(
            chair_stand_5_time_sec=Decimal("11.2"),
            walk_6m_time_sec=Decimal("6.5"),
            walk_6m_distance_m=Decimal("6.0"),
        ),
    )

    assert response.physical_assessment_id == 30
    assert response.walk_6m_speed_mps == Decimal("0.92")
    assert response.used_for_level_setting is True
    assert response.activity_profile.current_level == ActivityLevel.NORMAL
    assert response.activity_profile.level_reason == LevelReason.INITIAL_TEST
    assert assessment_repo.created is not None
    assert assessment_repo.created.used_for_level_setting is True
    assert activity_repo.created is not None
    assert activity_repo.created.physical_assessment_id == 30
    assert session.committed is True


async def test_create_assessment_logs_activity_level_change() -> None:
    existing = UserActivityProfile(
        activity_profile_id=100,
        user_id=1,
        current_level=ActivityLevel.HARD,
        level_reason=LevelReason.USER_SELECTED,
        physical_assessment_id=10,
        started_at=datetime(2026, 7, 10, 12, 0, 0),
    )
    service, _, activity_repo, _ = _service(existing)
    user = cast(User, SimpleNamespace(user_id=1))

    response = await service.create_assessment(
        user,
        PhysicalAssessmentCreateRequest(
            chair_stand_5_time_sec=Decimal("11.2"),
            walk_6m_time_sec=Decimal("6.5"),
            walk_6m_distance_m=Decimal("6.0"),
        ),
    )

    assert response.activity_profile.current_level == ActivityLevel.NORMAL
    assert response.activity_profile.level_reason == LevelReason.RULE
    assert activity_repo.created_level_change_log is not None
    assert activity_repo.created_level_change_log.from_level == ActivityLevel.HARD
    assert activity_repo.created_level_change_log.to_level == ActivityLevel.NORMAL
    assert activity_repo.created_level_change_log.reason_type == ReasonType.RULE
    assert activity_repo.created_level_change_log.reason_text == "physical_assessment:30"
    assert activity_repo.created_level_change_log.accepted_by_user is False


async def test_create_assessment_keeps_level_when_walk_skipped() -> None:
    existing = UserActivityProfile(
        activity_profile_id=100,
        user_id=1,
        current_level=ActivityLevel.NORMAL,
        level_reason=LevelReason.INITIAL_TEST,
        physical_assessment_id=10,
        started_at=datetime(2026, 7, 10, 12, 0, 0),
    )
    service, assessment_repo, activity_repo, session = _service(existing)
    user = cast(User, SimpleNamespace(user_id=1))

    response = await service.create_assessment(
        user,
        PhysicalAssessmentCreateRequest(
            chair_stand_skipped=True,
            walk_6m_skipped=True,
        ),
    )

    assert response.used_for_level_setting is False
    assert response.activity_profile.current_level == ActivityLevel.NORMAL
    assert assessment_repo.created is not None
    assert assessment_repo.created.used_for_level_setting is False
    assert activity_repo.updated is None
    assert activity_repo.created is None
    assert activity_repo.created_level_change_log is None
    assert session.committed is True

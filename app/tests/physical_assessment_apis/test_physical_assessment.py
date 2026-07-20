from datetime import date, datetime
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

# 밴드는 5STS 단독 산출(팀 결정 2026-07-20). 6m 걷기는 확장/기록용이라 밴드에 쓰지 않는다.
# 서비스 테스트용 기본 생년: 나이 71 → 70-79 연령대(평균 12.6초). chair_stand 11.2 ≤ 12.6 → 중.
_DEFAULT_BIRTH = date(1955, 1, 1)


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


class _FakeHealthProfileRepository:
    """연령대 평균 산출에 필요한 생년만 제공(없으면 나이 미상)."""

    def __init__(self, birth_date: date | None = _DEFAULT_BIRTH) -> None:
        self.birth_date = birth_date

    async def get_latest_profile(self, user_id: int) -> object | None:
        if self.birth_date is None:
            return None
        return SimpleNamespace(birth_date=self.birth_date)


def _service(
    profile: UserActivityProfile | None = None,
    birth_date: date | None = _DEFAULT_BIRTH,
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
    service.health_repo = _FakeHealthProfileRepository(birth_date)  # type: ignore[assignment]
    return service, assessment_repo, activity_repo, session


# ---------------- DTO 검증 ----------------


def test_physical_assessment_requires_chair_stand_time_when_not_skipped() -> None:
    with pytest.raises(ValidationError):
        PhysicalAssessmentCreateRequest(walk_6m_time_sec=Decimal("7.2"))


def test_walk_6m_is_optional_extension_not_required() -> None:
    # 6m 걷기는 밴드 미사용 확장/기록용 → 생략해도 유효(더 이상 필수 아님).
    data = PhysicalAssessmentCreateRequest(chair_stand_5_time_sec=Decimal("12.3"))
    assert data.walk_6m_time_sec is None


def test_walk_6m_distance_without_time_rejected() -> None:
    # 거리만 있고 시간이 없으면 속도 산출 불가 → 거부(리뷰 #103-2).
    with pytest.raises(ValidationError):
        PhysicalAssessmentCreateRequest(
            chair_stand_5_time_sec=Decimal("11.0"), walk_6m_distance_m=Decimal("6.0")
        )


def test_physical_assessment_allows_skipped_measurements() -> None:
    data = PhysicalAssessmentCreateRequest(chair_stand_skipped=True, walk_6m_skipped=True)

    assert data.chair_stand_5_time_sec is None
    assert data.walk_6m_time_sec is None


def test_walk_speed_uses_two_decimal_places() -> None:
    speed = PhysicalAssessmentService._calculate_walk_speed(Decimal("6.00"), Decimal("7.00"))

    assert speed == Decimal("0.86")


# ---------------- 밴드 산출(5STS 단독) ----------------


def test_age_norm_5sts_by_age_band() -> None:
    assert PhysicalAssessmentService._age_norm_5sts(67) == Decimal("11.4")  # 65-69
    assert PhysicalAssessmentService._age_norm_5sts(75) == Decimal("12.6")  # 70-79
    assert PhysicalAssessmentService._age_norm_5sts(89) == Decimal("14.8")  # 80-89 (규준 상한)
    assert PhysicalAssessmentService._age_norm_5sts(90) is None  # 90+ 규준 범위 밖 → 밴드 미산출(하)
    assert PhysicalAssessmentService._age_norm_5sts(None) is None  # 나이 미상


def test_activity_level_from_5sts_vs_age_norm() -> None:
    # 5STS ≤ 연령대 평균 → 중, 초과 → 하. 콜드스타트는 상(hard) 없음.
    assert (
        PhysicalAssessmentService._determine_activity_level(
            chair_stand_sec=Decimal("11.4"),
            age_norm_sec=Decimal("11.4"),
            pain_reported=False,
            dizziness_reported=False,
        )
        == ActivityLevel.NORMAL
    )
    assert (
        PhysicalAssessmentService._determine_activity_level(
            chair_stand_sec=Decimal("15.0"),
            age_norm_sec=Decimal("12.6"),
            pain_reported=False,
            dizziness_reported=False,
        )
        == ActivityLevel.EASY
    )


def test_activity_level_easy_when_measurement_or_age_missing() -> None:
    # 미실시(chair_stand None) 또는 연령 미상 → 하(기본).
    assert (
        PhysicalAssessmentService._determine_activity_level(
            chair_stand_sec=None,
            age_norm_sec=Decimal("12.6"),
            pain_reported=False,
            dizziness_reported=False,
        )
        == ActivityLevel.EASY
    )
    assert (
        PhysicalAssessmentService._determine_activity_level(
            chair_stand_sec=Decimal("9.0"),
            age_norm_sec=None,
            pain_reported=False,
            dizziness_reported=False,
        )
        == ActivityLevel.EASY
    )


def test_activity_level_falls_back_to_easy_for_safety_flags() -> None:
    # 아주 빠른 5STS(잘함)여도 통증/어지럼이면 안전상 하.
    assert (
        PhysicalAssessmentService._determine_activity_level(
            chair_stand_sec=Decimal("8.0"),
            age_norm_sec=Decimal("12.6"),
            pain_reported=True,
            dizziness_reported=False,
        )
        == ActivityLevel.EASY
    )


# ---------------- create_assessment 통합 ----------------


async def test_create_assessment_sets_activity_profile_from_5sts() -> None:
    service, assessment_repo, activity_repo, session = _service()
    user = cast(User, SimpleNamespace(user_id=1))

    response = await service.create_assessment(
        user,
        PhysicalAssessmentCreateRequest(
            chair_stand_5_time_sec=Decimal("11.2"),  # ≤ 12.6(70-79 평균) → 중
            walk_6m_time_sec=Decimal("6.5"),  # 확장/기록: 속도만 저장, 밴드엔 미사용
            walk_6m_distance_m=Decimal("6.0"),
        ),
    )

    assert response.physical_assessment_id == 30
    assert response.walk_6m_speed_mps == Decimal("0.92")  # 기록용 속도는 여전히 계산·저장
    assert response.used_for_level_setting is True
    assert response.activity_profile.current_level == ActivityLevel.NORMAL
    assert response.activity_profile.level_reason == LevelReason.INITIAL_TEST
    assert assessment_repo.created is not None
    assert assessment_repo.created.used_for_level_setting is True
    assert activity_repo.created is not None
    assert activity_repo.created.physical_assessment_id == 30
    assert session.committed is True


async def test_create_assessment_slow_5sts_sets_easy() -> None:
    # 연령대 평균 초과(느림) → 하. (6m 걷기 없이 5STS 단독 제출)
    service, _, _, _ = _service()
    user = cast(User, SimpleNamespace(user_id=1))

    response = await service.create_assessment(
        user,
        PhysicalAssessmentCreateRequest(chair_stand_5_time_sec=Decimal("15.0"), walk_6m_skipped=True),
    )

    assert response.used_for_level_setting is True
    assert response.activity_profile.current_level == ActivityLevel.EASY


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
        PhysicalAssessmentCreateRequest(chair_stand_5_time_sec=Decimal("11.2")),
    )

    assert response.activity_profile.current_level == ActivityLevel.NORMAL
    assert response.activity_profile.level_reason == LevelReason.RULE
    assert activity_repo.created_level_change_log is not None
    assert activity_repo.created_level_change_log.from_level == ActivityLevel.HARD
    assert activity_repo.created_level_change_log.to_level == ActivityLevel.NORMAL
    assert activity_repo.created_level_change_log.reason_type == ReasonType.RULE
    assert activity_repo.created_level_change_log.reason_text == "physical_assessment:30"
    assert activity_repo.created_level_change_log.accepted_by_user is False


async def test_create_assessment_skipped_5sts_falls_to_easy() -> None:
    # 팀 결정: 5STS 미실시/스킵 → 하(기본). 기존 hard 사용자도 하로 수렴한다(리뷰 #103-1).
    existing = UserActivityProfile(
        activity_profile_id=100,
        user_id=1,
        current_level=ActivityLevel.HARD,
        level_reason=LevelReason.USER_SELECTED,
        physical_assessment_id=10,
        started_at=datetime(2026, 7, 10, 12, 0, 0),
    )
    service, assessment_repo, activity_repo, session = _service(existing)
    user = cast(User, SimpleNamespace(user_id=1))

    response = await service.create_assessment(
        user,
        PhysicalAssessmentCreateRequest(chair_stand_skipped=True, walk_6m_skipped=True),
    )

    assert response.used_for_level_setting is True
    assert response.activity_profile.current_level == ActivityLevel.EASY
    assert assessment_repo.created is not None
    assert activity_repo.updated is not None
    assert activity_repo.created_level_change_log is not None
    assert activity_repo.created_level_change_log.from_level == ActivityLevel.HARD
    assert activity_repo.created_level_change_log.to_level == ActivityLevel.EASY
    assert session.committed is True


async def test_create_assessment_normalizes_omitted_walk_to_skipped() -> None:
    # 6m 시간 미제공 → 스킵으로 정규화 저장(모순 상태 방지, 리뷰 #103-2).
    service, assessment_repo, _, _ = _service()
    user = cast(User, SimpleNamespace(user_id=1))

    await service.create_assessment(
        user, PhysicalAssessmentCreateRequest(chair_stand_5_time_sec=Decimal("11.0"))
    )

    assert assessment_repo.created is not None
    assert assessment_repo.created.walk_6m_skipped is True
    assert assessment_repo.created.walk_6m_speed_mps is None

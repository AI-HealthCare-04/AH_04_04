from datetime import datetime
from decimal import Decimal
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.dtos.physical_assessment import PhysicalAssessmentCreateRequest, PhysicalAssessmentResponse
from app.models.enums import AssessmentType, HealthCheckStatus, InputMethod
from app.models.health import HealthCheckSession, PhysicalAssessment
from app.models.users import User
from app.services.physical_assessment import PhysicalAssessmentService

# 난이도 폐기(#428): 5STS 는 측정 기록·추이(#353)·안전망 카드 입력으로만 저장된다.
#   활동수준 산출·프로필 upsert 는 제거됐고, 응답은 physical_assessment_id 만 담는다.


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
        # COMPLETED 재제출 분기(#180 2b)에서 기존 저장을 돌려주기 위한 사전 저장물.
        self.stored: PhysicalAssessment | None = None

    async def create_physical_assessment(self, assessment: PhysicalAssessment) -> PhysicalAssessment:
        assessment.physical_assessment_id = 30
        self.created = assessment
        return assessment

    async def get_by_session(self, session_id: int, user_id: int) -> PhysicalAssessment | None:
        return self.stored


class _FakeHealthCheckRepository:
    def __init__(self, health_check_session: HealthCheckSession | None) -> None:
        self.health_check_session = health_check_session
        self.updated: HealthCheckSession | None = None
        # 전이 경로가 세션 행을 잠그고 읽는지(#207 동시성) 확인용.
        self.locked_for_update = False

    async def get_session(
        self, session_id: int, user_id: int, *, for_update: bool = False
    ) -> HealthCheckSession | None:
        self.locked_for_update = self.locked_for_update or for_update
        return self.health_check_session

    async def update_session(self, health_check_session: HealthCheckSession) -> HealthCheckSession:
        self.updated = health_check_session
        return health_check_session


def _service() -> tuple[PhysicalAssessmentService, _FakePhysicalAssessmentRepository, _FakeSession]:
    session = _FakeSession()
    service = PhysicalAssessmentService(cast(AsyncSession, session))
    assessment_repo = _FakePhysicalAssessmentRepository()
    service.repo = assessment_repo  # type: ignore[assignment]
    return service, assessment_repo, session


# ---------------- DTO 검증 ----------------


def test_physical_assessment_requires_chair_stand_time_when_not_skipped() -> None:
    # 5STS는 스킵이 아니면 필수. (6m 걷기는 제거됨 #109)
    with pytest.raises(ValidationError):
        PhysicalAssessmentCreateRequest()


def test_physical_assessment_allows_skipped_measurements() -> None:
    data = PhysicalAssessmentCreateRequest(chair_stand_skipped=True)

    assert data.chair_stand_5_time_sec is None


def test_deprecated_walk_6m_fields_are_ignored_not_rejected() -> None:
    # 구버전 앱의 walk_6m_* payload는 422로 막지 않고 무시한다(deprecated no-op, 리뷰 #118-3).
    data = PhysicalAssessmentCreateRequest.model_validate(
        {
            "chair_stand_5_time_sec": 11.0,
            "walk_6m_time_sec": 6.5,
            "walk_6m_distance_m": 6.0,
            "walk_6m_skipped": False,
        }
    )

    assert data.chair_stand_5_time_sec is not None
    assert not hasattr(data, "walk_6m_time_sec")
    assert not hasattr(data, "walk_6m_skipped")


def test_response_shape_has_only_assessment_id() -> None:
    # 난이도 폐기(#428) 계약: 응답은 physical_assessment_id 하나만 — level/프로필 필드 회귀 방지.
    assert set(PhysicalAssessmentResponse.model_fields.keys()) == {"physical_assessment_id"}


# ---------------- 5STS 시간 정규화 ----------------


def test_quantize_5sts_normalizes_to_two_decimals() -> None:
    # 저장 컬럼(Numeric(5,2))과 같은 2자리(HALF_UP)로 정규화. None 은 그대로.
    assert PhysicalAssessmentService._quantize_5sts(Decimal("11.2")) == Decimal("11.20")
    assert PhysicalAssessmentService._quantize_5sts(Decimal("11.005")) == Decimal("11.01")
    assert PhysicalAssessmentService._quantize_5sts(None) is None


# ---------------- create_assessment 통합 ----------------


async def test_create_assessment_stores_measurement() -> None:
    service, assessment_repo, session = _service()
    user = cast(User, SimpleNamespace(user_id=1))

    response = await service.create_assessment(
        user,
        PhysicalAssessmentCreateRequest(chair_stand_5_time_sec=Decimal("11.2")),
    )

    assert response.physical_assessment_id == 30
    assert assessment_repo.created is not None
    assert assessment_repo.created.chair_stand_5_time_sec == Decimal("11.2")
    assert assessment_repo.created.chair_stand_skipped is False
    assert session.committed is True


async def test_create_assessment_saves_used_for_level_setting_false() -> None:
    # 난이도 폐기 이후 저장분: 컬럼은 과거 데이터 사실 기록용으로만 남아, 신규 행은 항상 False.
    service, assessment_repo, _ = _service()

    await service.create_assessment(
        cast(User, SimpleNamespace(user_id=1)),
        PhysicalAssessmentCreateRequest(chair_stand_5_time_sec=Decimal("11.2")),
    )

    assert assessment_repo.created is not None
    assert assessment_repo.created.used_for_level_setting is False


async def test_create_assessment_completes_linked_started_session() -> None:
    service, _, session = _service()
    health_check_session = HealthCheckSession(
        session_id=10,
        user_id=1,
        status=HealthCheckStatus.STARTED,
        input_method=InputMethod.FORM,
        has_estimated_value=False,
        created_at=datetime(2026, 7, 10, 12, 0, 0),
        completed_at=None,
    )
    health_check_repo = _FakeHealthCheckRepository(health_check_session)
    service.health_check_repo = health_check_repo  # type: ignore[assignment]

    await service.create_assessment(
        cast(User, SimpleNamespace(user_id=1)),
        PhysicalAssessmentCreateRequest(
            session_id=10,
            chair_stand_5_time_sec=Decimal("11.2"),
        ),
    )

    assert health_check_repo.updated is health_check_session
    assert health_check_session.status == HealthCheckStatus.COMPLETED
    assert health_check_session.completed_at is not None
    assert session.committed is True
    # 전이 경로는 세션 행을 잠그고 읽어 동시 건너뛰기와 직렬화한다(#207 동시성).
    assert health_check_repo.locked_for_update is True


async def test_create_assessment_rejects_finished_session() -> None:
    service, assessment_repo, session = _service()
    health_check_session = HealthCheckSession(
        session_id=10,
        user_id=1,
        status=HealthCheckStatus.SKIPPED,
        input_method=InputMethod.FORM,
        has_estimated_value=False,
        created_at=datetime(2026, 7, 10, 12, 0, 0),
        completed_at=datetime(2026, 7, 10, 12, 1, 0),
    )
    service.health_check_repo = _FakeHealthCheckRepository(health_check_session)  # type: ignore[assignment]

    with pytest.raises(HTTPException) as exc:
        await service.create_assessment(
            cast(User, SimpleNamespace(user_id=1)),
            PhysicalAssessmentCreateRequest(
                session_id=10,
                chair_stand_5_time_sec=Decimal("11.2"),
            ),
        )

    assert exc.value.status_code == 409
    assert assessment_repo.created is None
    assert session.committed is False


def _completed_session() -> HealthCheckSession:
    return HealthCheckSession(
        session_id=10,
        user_id=1,
        status=HealthCheckStatus.COMPLETED,
        input_method=InputMethod.FORM,
        has_estimated_value=False,
        created_at=datetime(2026, 7, 10, 12, 0, 0),
        completed_at=datetime(2026, 7, 10, 12, 1, 0),
    )


def _stored_assessment() -> PhysicalAssessment:
    # 컬럼 정밀도(Numeric(5,2))대로 11.20 으로 저장된 기존 결과.
    return PhysicalAssessment(
        physical_assessment_id=30,
        user_id=1,
        session_id=10,
        assessment_type=AssessmentType.INITIAL,
        chair_stand_5_time_sec=Decimal("11.20"),
        chair_stand_skipped=False,
        pain_reported=False,
        dizziness_reported=False,
        used_for_level_setting=False,
    )


async def test_create_assessment_completed_session_same_payload_is_idempotent() -> None:
    # 응답 유실 재전송(#180): 이미 COMPLETED 된 세션에 같은 내용 재제출 →
    #   새로 만들지 않고 기존 결과를 그대로 돌려준다(멱등). 11.2 vs 저장 11.20 은 2자리 정규화로 동일.
    service, assessment_repo, session = _service()
    assessment_repo.stored = _stored_assessment()
    service.health_check_repo = _FakeHealthCheckRepository(_completed_session())  # type: ignore[assignment]

    response = await service.create_assessment(
        cast(User, SimpleNamespace(user_id=1)),
        PhysicalAssessmentCreateRequest(session_id=10, chair_stand_5_time_sec=Decimal("11.2")),
    )

    assert response.physical_assessment_id == 30
    assert assessment_repo.created is None  # 재생성 없음
    assert session.committed is False  # 재제출은 아무것도 커밋하지 않는다


async def test_create_assessment_completed_session_different_payload_conflicts() -> None:
    # #180 완료기준(리뷰 #207 2b): 이미 완료된 세션에 '다른' 내용 제출 →
    #   확정된 결과를 덮어쓰지 않도록 409. 같은 값의 멱등 재시도와 구분된다.
    service, assessment_repo, session = _service()
    assessment_repo.stored = _stored_assessment()  # 저장값 11.20
    service.health_check_repo = _FakeHealthCheckRepository(_completed_session())  # type: ignore[assignment]

    with pytest.raises(HTTPException) as exc:
        await service.create_assessment(
            cast(User, SimpleNamespace(user_id=1)),
            PhysicalAssessmentCreateRequest(session_id=10, chair_stand_5_time_sec=Decimal("13.5")),
        )

    assert exc.value.status_code == 409
    assert assessment_repo.created is None
    assert session.committed is False


async def test_create_assessment_rejects_missing_session() -> None:
    service, assessment_repo, session = _service()
    service.health_check_repo = _FakeHealthCheckRepository(None)  # type: ignore[assignment]

    with pytest.raises(HTTPException) as exc:
        await service.create_assessment(
            cast(User, SimpleNamespace(user_id=1)),
            PhysicalAssessmentCreateRequest(
                session_id=999,
                chair_stand_5_time_sec=Decimal("11.2"),
            ),
        )

    assert exc.value.status_code == 404
    assert exc.value.detail == "세션을 찾을 수 없습니다."
    assert assessment_repo.created is None
    assert session.committed is False

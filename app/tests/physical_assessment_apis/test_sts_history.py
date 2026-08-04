"""5STS 이력(#353): 측정 기록만 이력에 담는다. (latest 단건 조회는 난이도 폐기 리팩터링에서 제거)

비의료 가드레일(#57): 응답은 시간·시각 등 사실만 — 판정 필드가 없음을 DTO 스키마로 고정한다.
"""

import asyncio
from collections.abc import Sequence
from datetime import datetime
from decimal import Decimal
from types import SimpleNamespace
from typing import cast

from app.dtos.physical_assessment import PhysicalAssessmentHistoryItem
from app.models.enums import AssessmentType
from app.models.users import User
from app.services.physical_assessment import PhysicalAssessmentService

_USER = cast(User, SimpleNamespace(user_id=1))


def _assessment(aid: int, sec: str, day: int) -> SimpleNamespace:
    return SimpleNamespace(
        physical_assessment_id=aid,
        assessment_type=AssessmentType.INITIAL,
        chair_stand_5_time_sec=Decimal(sec),
        created_at=datetime(2026, 7, day, 10, 0, 0),
    )


def _service(rows: Sequence[object]) -> PhysicalAssessmentService:
    service = PhysicalAssessmentService(session=None)  # type: ignore[arg-type]
    captured: dict[str, int] = {}

    async def fake_history(user_id: int, limit: int) -> list[object]:
        captured["limit"] = limit
        return list(rows[:limit])

    service.repo.get_measured_history = fake_history  # type: ignore[assignment]
    service._captured = captured  # type: ignore[attr-defined]
    return service


def test_history_orders_and_limits() -> None:
    rows = [_assessment(3, "10.10", day=31), _assessment(2, "10.80", day=30), _assessment(1, "12.50", day=1)]
    service = _service(rows)
    resp = asyncio.run(service.get_measured_history(_USER, limit=2))
    assert [a.physical_assessment_id for a in resp.assessments] == [3, 2]
    assert resp.assessments[0].chair_stand_5_time_sec == 10.10
    assert service._captured["limit"] == 2  # type: ignore[attr-defined]


def test_history_item_exposes_facts_only() -> None:
    # 비의료(#57): 판정성 필드(예: '저하')가 스키마에 없어야 한다 — 시간·유형·시각·id 만.
    assert set(PhysicalAssessmentHistoryItem.model_fields.keys()) == {
        "physical_assessment_id",
        "assessment_type",
        "chair_stand_5_time_sec",
        "created_at",
    }

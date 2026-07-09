import asyncio
from datetime import date
from typing import cast

import pytest
from fastapi import HTTPException

from app.dtos.dashboard import HomeAvailableMissionSummary
from app.models.users import User
from app.services.dashboard import DashboardService

# 카운팅 로직만 검증하므로 user는 스텁된 get_missions로 전달만 되고 실제로 쓰이지 않는다.
_USER = cast(User, object())


class _FakeMission:
    def __init__(self, mission_type: str) -> None:
        self.mission_type = mission_type


def _service_with_missions(missions: list[_FakeMission]) -> DashboardService:
    # DB 접근 없이 카운팅 로직만 검증하기 위해 mission_service.get_missions를 스텁한다.
    service = DashboardService(session=None)  # type: ignore[arg-type]

    async def fake_get_missions(user: object, mission_type: object = None, level: object = None) -> list[_FakeMission]:
        return missions

    service.mission_service.get_missions = fake_get_missions  # type: ignore[assignment]
    return service


def test_available_mission_summary_counts_by_type() -> None:
    service = _service_with_missions(
        [
            _FakeMission("meal"),
            _FakeMission("meal"),
            _FakeMission("exercise"),
            _FakeMission("walking"),
            _FakeMission("game"),
        ]
    )

    result = asyncio.run(service._available_mission_summary(_USER))

    assert result == HomeAvailableMissionSummary(meal=2, exercise=1, walking=1, game=1)


def test_available_mission_summary_skips_unknown_type() -> None:
    # enum에 없는 타입("bogus")이 섞여도 500 없이 건너뛴다.
    service = _service_with_missions([_FakeMission("meal"), _FakeMission("bogus")])

    result = asyncio.run(service._available_mission_summary(_USER))

    assert result == HomeAvailableMissionSummary(meal=1, exercise=0, walking=0, game=0)


def test_available_mission_summary_empty() -> None:
    service = _service_with_missions([])

    result = asyncio.run(service._available_mission_summary(_USER))

    assert result == HomeAvailableMissionSummary(meal=0, exercise=0, walking=0, game=0)


def test_month_range_returns_first_and_last_day() -> None:
    # 2월(윤년 아님) 말일까지 정확히 계산한다.
    assert DashboardService._month_range("2026-02") == (date(2026, 2, 1), date(2026, 2, 28))
    assert DashboardService._month_range("2026-07") == (date(2026, 7, 1), date(2026, 7, 31))


@pytest.mark.parametrize("bad_month", ["2026", "2026-7", "2026-13", "2026-00", "not-a-month", "2026-07-01", ""])
def test_month_range_rejects_invalid_format(bad_month: str) -> None:
    with pytest.raises(HTTPException) as exc:
        DashboardService._month_range(bad_month)
    assert exc.value.status_code == 400

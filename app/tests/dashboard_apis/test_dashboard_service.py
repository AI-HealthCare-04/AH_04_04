import asyncio
from datetime import date
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi import HTTPException, status

from app.dtos.dashboard import HomeAvailableMissionSummary, HomeLatestPrediction
from app.models.enums import ActivityLevel
from app.models.users import User
from app.services.dashboard import DashboardService

# 카운팅 로직만 검증하므로 user는 스텁된 get_missions로 전달만 되고 실제로 쓰이지 않는다.
_USER = cast(User, object())
# get_home은 user.nickname을 읽으므로 홈 테스트에는 닉네임이 있는 사용자를 쓴다.
_HOME_USER = cast(User, SimpleNamespace(user_id=1, nickname="테스터"))


class _FakeMission:
    def __init__(self, mission_type: str) -> None:
        self.mission_type = mission_type


class _FakePrediction:
    def __init__(self, care_stage: str, display_message: str) -> None:
        self.care_stage = care_stage
        self.display_message = display_message


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

    result = asyncio.run(service._available_mission_summary(_USER, ActivityLevel.EASY))

    assert result == HomeAvailableMissionSummary(meal=2, exercise=1, walking=1, game=1)


def test_available_mission_summary_skips_unknown_type() -> None:
    # enum에 없는 타입("bogus")이 섞여도 500 없이 건너뛴다.
    service = _service_with_missions([_FakeMission("meal"), _FakeMission("bogus")])

    result = asyncio.run(service._available_mission_summary(_USER, ActivityLevel.EASY))

    assert result == HomeAvailableMissionSummary(meal=1, exercise=0, walking=0, game=0)


def test_available_mission_summary_empty() -> None:
    service = _service_with_missions([])

    result = asyncio.run(service._available_mission_summary(_USER, ActivityLevel.EASY))

    assert result == HomeAvailableMissionSummary(meal=0, exercise=0, walking=0, game=0)


def test_available_mission_summary_forwards_level_to_get_missions() -> None:
    # 표시 레벨과 카운트 산정 레벨을 일치시키기 위해, 전달한 level이 get_missions로 그대로 넘어가는지 검증.
    captured: dict[str, object] = {}
    service = DashboardService(session=None)  # type: ignore[arg-type]

    async def fake_get_missions(user: object, mission_type: object = None, level: object = None) -> list[object]:
        captured["level"] = level
        return []

    service.mission_service.get_missions = fake_get_missions  # type: ignore[assignment]

    asyncio.run(service._available_mission_summary(_USER, ActivityLevel.EASY))

    assert captured["level"] == ActivityLevel.EASY


def _service_with_risk(*, result: object = None, raises: Exception | None = None) -> DashboardService:
    service = DashboardService(session=None)  # type: ignore[arg-type]

    async def fake_get_latest(user: object) -> object:
        if raises is not None:
            raise raises
        return result

    service.risk_service.get_latest_prediction = fake_get_latest  # type: ignore[assignment]
    return service


def test_latest_prediction_maps_when_present() -> None:
    service = _service_with_risk(result=_FakePrediction("good", "지금처럼 이어가 보세요."))

    result = asyncio.run(service._latest_prediction(_USER))

    assert result == HomeLatestPrediction(care_stage="good", display_message="지금처럼 이어가 보세요.")


def test_latest_prediction_none_when_prediction_missing() -> None:
    # 예측 없음(단독 조회 메서드의 404)은 홈에서 null로 변환된다.
    service = _service_with_risk(raises=HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="none"))

    assert asyncio.run(service._latest_prediction(_USER)) is None


def test_latest_prediction_reraises_non_404() -> None:
    service = _service_with_risk(raises=HTTPException(status_code=500, detail="boom"))

    with pytest.raises(HTTPException):
        asyncio.run(service._latest_prediction(_USER))


def _service_for_home(*, profile: object | None) -> tuple[DashboardService, dict[str, object]]:
    # get_home의 모든 의존을 스텁해 DB 없이 난이도 연결만 검증한다.
    service = DashboardService(session=None)  # type: ignore[arg-type]
    captured: dict[str, object] = {}

    async def fake_get_current_points(user_id: object) -> int:
        return 0

    async def fake_get_today_summary(user_id: object) -> object:
        return None

    async def fake_get_by_user_id(user_id: object) -> object:
        return profile

    async def fake_get_missions(user: object, mission_type: object = None, level: object = None) -> list[object]:
        captured["mission_level"] = level
        return []

    async def fake_get_latest(user: object) -> object:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="none")

    service.repo.get_current_points = fake_get_current_points  # type: ignore[assignment]
    service.repo.get_today_summary = fake_get_today_summary  # type: ignore[assignment]
    service.activity_repo.get_by_user_id = fake_get_by_user_id  # type: ignore[assignment]
    service.mission_service.get_missions = fake_get_missions  # type: ignore[assignment]
    service.risk_service.get_latest_prediction = fake_get_latest  # type: ignore[assignment]
    return service, captured


def test_get_home_uses_real_activity_level() -> None:
    # 프로필이 있으면 홈 표시 난이도가 실제 current_level을 따르고, 미션 수 산정도 같은 레벨로 한다.
    service, captured = _service_for_home(profile=SimpleNamespace(current_level=ActivityLevel.HARD))

    result = asyncio.run(service.get_home(_HOME_USER))

    assert result.activity_profile.current_level == ActivityLevel.HARD
    assert captured["mission_level"] == ActivityLevel.HARD  # 표시 레벨 == 카운트 레벨


def test_get_home_defaults_easy_when_no_profile() -> None:
    # 프로필이 아직 없으면(건강체크 스킵/기초체력검사 전) 기본 easy로 본다.
    service, captured = _service_for_home(profile=None)

    result = asyncio.run(service.get_home(_HOME_USER))

    assert result.activity_profile.current_level == ActivityLevel.EASY
    assert captured["mission_level"] == ActivityLevel.EASY


def test_month_range_returns_first_and_last_day() -> None:
    # 2월(윤년 아님) 말일까지 정확히 계산한다.
    assert DashboardService._month_range("2026-02") == (date(2026, 2, 1), date(2026, 2, 28))
    assert DashboardService._month_range("2026-07") == (date(2026, 7, 1), date(2026, 7, 31))


@pytest.mark.parametrize("bad_month", ["2026", "2026-7", "2026-13", "2026-00", "not-a-month", "2026-07-01", ""])
def test_month_range_rejects_invalid_format(bad_month: str) -> None:
    with pytest.raises(HTTPException) as exc:
        DashboardService._month_range(bad_month)
    assert exc.value.status_code == 400

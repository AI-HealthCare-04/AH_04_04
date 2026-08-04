import asyncio
from datetime import date, timedelta
from decimal import Decimal
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi import HTTPException, status

from app.core.utils.clock import today_kst
from app.dtos.dashboard import HomeAvailableMissionSummary, HomeLatestPrediction
from app.models.dashboard import DailyActivitySummary
from app.models.users import User
from app.services.dashboard import DashboardService

# 카운팅 로직만 검증하므로 user는 스텁된 get_missions로 전달만 되고 실제로 쓰이지 않는다.
_USER = cast(User, object())
# user_id를 읽는 경로(get_prediction_inputs 등)에는 id가 있는 사용자를 쓴다.
_USER_WITH_ID = cast(User, SimpleNamespace(user_id=1))
# get_home은 user.nickname을 읽으므로 홈 테스트에는 닉네임이 있는 사용자를 쓴다.
_HOME_USER = cast(User, SimpleNamespace(user_id=1, nickname="테스터"))


class _FakeMission:
    def __init__(self, mission_type: str, daily_count_limit: int | None = None) -> None:
        self.mission_type = mission_type
        self.daily_count_limit = daily_count_limit


class _FakePrediction:
    def __init__(self, care_stage: str, display_message: str) -> None:
        self.care_stage = care_stage
        self.display_message = display_message


def _service_with_missions(missions: list[_FakeMission]) -> DashboardService:
    # DB 접근 없이 카운팅 로직만 검증하기 위해 mission_service.get_missions를 스텁한다.
    service = DashboardService(session=None)  # type: ignore[arg-type]

    async def fake_get_missions(user: object, mission_type: object = None) -> list[_FakeMission]:
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


def test_available_mission_summary_excludes_daily_limited_when_done_today() -> None:
    # 식사(daily_count_limit=1)를 오늘 이미 카운트했으면 '가능한 미션'에서 제외된다(반복형 걷기는 유지).
    service = _service_with_missions([_FakeMission("meal", daily_count_limit=1), _FakeMission("walking")])
    today = cast(
        DailyActivitySummary, SimpleNamespace(meal_counted=True, exercise_count=0, walking_count=0, game_count=0)
    )

    result = asyncio.run(service._available_mission_summary(_USER, today))

    assert result == HomeAvailableMissionSummary(meal=0, exercise=0, walking=1, game=0)


def test_available_mission_summary_keeps_daily_limited_when_not_done_today() -> None:
    # 오늘 식사를 아직 안 했으면 그대로 수행 가능.
    service = _service_with_missions([_FakeMission("meal", daily_count_limit=1)])
    today = cast(
        DailyActivitySummary, SimpleNamespace(meal_counted=False, exercise_count=0, walking_count=0, game_count=0)
    )

    result = asyncio.run(service._available_mission_summary(_USER, today))

    assert result == HomeAvailableMissionSummary(meal=1, exercise=0, walking=0, game=0)


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


def _service_for_home() -> tuple[DashboardService, dict[str, object]]:
    # get_home의 모든 의존을 스텁해 DB 없이 홈 조립 로직만 검증한다.
    service = DashboardService(session=None)  # type: ignore[arg-type]
    captured: dict[str, object] = {}

    async def fake_get_current_points(user_id: object) -> int:
        return 0

    async def fake_get_today_summary(user_id: object) -> object:
        return None

    async def fake_get_counted_summary_dates(user_id: object, through_date: object) -> list[date]:
        return captured.get("completed_dates", [])  # type: ignore[return-value]

    async def fake_get_missions(user: object, mission_type: object = None) -> list[object]:
        return []

    async def fake_get_latest(user: object) -> object:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="none")

    async def fake_today_walking(user: object) -> tuple[float, int]:
        return captured.get("walking", (0.0, 0))  # type: ignore[return-value]

    service.repo.get_current_points = fake_get_current_points  # type: ignore[assignment]
    service.repo.get_today_summary = fake_get_today_summary  # type: ignore[assignment]
    service.repo.get_counted_summary_dates = fake_get_counted_summary_dates  # type: ignore[assignment]
    service.mission_service.get_missions = fake_get_missions  # type: ignore[assignment]
    service.mission_service.get_today_walking_totals = fake_today_walking  # type: ignore[assignment]
    service.risk_service.get_latest_prediction = fake_get_latest  # type: ignore[assignment]
    return service, captured


def test_get_home_includes_today_walking_totals() -> None:
    # 홈 '오늘 걷기' 위젯: 당일 누적 실적(분·걸음)을 미션 도메인 원천에서 그대로 담는다.
    service, captured = _service_for_home()
    captured["walking"] = (22.0, 2350)

    result = asyncio.run(service.get_home(_HOME_USER))

    assert result.today_walking.daily_total_min == 22.0
    assert result.today_walking.daily_total_steps == 2350


def test_get_home_today_walking_defaults_to_zero_when_no_walking() -> None:
    # 걷기 안 한 날도 null 아니라 {0, 0}으로 내려 앱 바인딩을 단순화한다.
    service, _ = _service_for_home()

    result = asyncio.run(service.get_home(_HOME_USER))

    assert result.today_walking.daily_total_min == 0.0
    assert result.today_walking.daily_total_steps == 0


def test_get_home_includes_server_authoritative_streak() -> None:
    service, captured = _service_for_home()
    today = today_kst()
    captured["completed_dates"] = [today, today - timedelta(days=1), today - timedelta(days=2)]

    result = asyncio.run(service.get_home(_HOME_USER))

    assert result.streak.current_days == 3
    assert result.streak.completed_today is True
    assert result.streak.as_of_date == today


def test_month_range_returns_first_and_last_day() -> None:
    # 2월(윤년 아님) 말일까지 정확히 계산한다.
    assert DashboardService._month_range("2026-02") == (date(2026, 2, 1), date(2026, 2, 28))
    assert DashboardService._month_range("2026-07") == (date(2026, 7, 1), date(2026, 7, 31))


@pytest.mark.parametrize("bad_month", ["2026", "2026-7", "2026-13", "2026-00", "not-a-month", "2026-07-01", ""])
def test_month_range_rejects_invalid_format(bad_month: str) -> None:
    with pytest.raises(HTTPException) as exc:
        DashboardService._month_range(bad_month)
    assert exc.value.status_code == 400


# ── 예측 대시보드 입력(#193) ─────────────────────────────────────────────────
def _service_with_prediction_stub(profile: object, active_days: tuple[int, int]) -> DashboardService:
    # DB 없이 매핑·clamp 로직만 검증하기 위해 두 레포 호출을 스텁한다.
    service = DashboardService(session=None)  # type: ignore[arg-type]

    async def fake_latest(user_id: int) -> object:
        return profile

    async def fake_count(user_id: int, start: date, end: date) -> tuple[int, int]:
        return active_days

    service.health_repo.get_latest_profile = fake_latest  # type: ignore[assignment]
    service.repo.count_active_days = fake_count  # type: ignore[assignment]
    return service


def test_prediction_inputs_maps_profile_and_clamps_days() -> None:
    profile = SimpleNamespace(
        sex=SimpleNamespace(value="male"),
        birth_date=date(1950, 3, 1),
        height_cm=Decimal("166.0"),
        weight_kg=Decimal("66.0"),
        waist_cm=Decimal("84.0"),
    )
    service = _service_with_prediction_stub(profile, active_days=(7, 2))
    out = asyncio.run(service.get_prediction_inputs(_USER_WITH_ID))
    assert out.sex == "male"
    assert out.birth_date == date(1950, 3, 1)
    assert out.height_cm == 166.0
    assert out.weight_kg == 66.0
    assert out.waist_cm == 84.0
    assert out.walk_days == 7
    assert out.musc_days == 2


def test_prediction_inputs_null_profile_falls_back_to_none() -> None:
    # 온보딩 미완(프로필 없음) → 신체값 null, 요일 수는 0.
    service = _service_with_prediction_stub(profile=None, active_days=(0, 0))
    out = asyncio.run(service.get_prediction_inputs(_USER_WITH_ID))
    assert out.sex is None
    assert out.birth_date is None
    assert out.height_cm is None
    assert out.weight_kg is None
    assert out.waist_cm is None
    assert out.walk_days == 0
    assert out.musc_days == 0


def test_prediction_inputs_none_waist_stays_none() -> None:
    # 허리 미측정(waist_cm None)이면 null 유지 → 허리 제외형 모델.
    profile = SimpleNamespace(
        sex=SimpleNamespace(value="female"),
        birth_date=date(1955, 6, 1),
        height_cm=Decimal("153.0"),
        weight_kg=Decimal("56.0"),
        waist_cm=None,
    )
    service = _service_with_prediction_stub(profile, active_days=(3, 1))
    out = asyncio.run(service.get_prediction_inputs(_USER_WITH_ID))
    assert out.sex == "female"
    assert out.waist_cm is None
    assert out.walk_days == 3
    assert out.musc_days == 1

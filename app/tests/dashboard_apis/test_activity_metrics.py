# =====================================================================================
# 활동량 환산 헬퍼(app/services/activity_metrics) 단위 테스트.
# 확정 공식(지영님 합의): moderate_equivalent_min = duration_min × (effective_MET / 3.0)
#   1) met_value 있으면 그대로  2) 없으면 activity_type 기본 MET
#   3) 기본 MET를 쓴 경우에만 intensity 보정  4) duration 없으면 0
# =====================================================================================
from datetime import date
from decimal import Decimal
from types import SimpleNamespace

from app.models.enums import ActivityType, Intensity
from app.services.activity_metrics import derive_activity_practice_flags, moderate_equivalent_min


def test_met_value_takes_priority_and_ignores_intensity() -> None:
    # met_value가 있으면 그대로 쓰고 intensity 보정은 하지 않는다. 6 MET·10분 → 10×6/3 = 20.
    result = moderate_equivalent_min(ActivityType.SEATED_EXERCISE, Intensity.HIGH, Decimal("10"), Decimal("6"))
    assert result == 20.0


def test_walking_uses_default_met() -> None:
    # 걷기는 met_value가 없어 기본 MET 3.0 → 10분이면 10×3/3 = 10 중강도상당분.
    result = moderate_equivalent_min(ActivityType.WALKING, None, Decimal("10"), None)
    assert result == 10.0


def test_seated_exercise_default_met() -> None:
    # 좌식 운동 기본 MET 2.5 → 12분이면 12×2.5/3 = 10.0.
    result = moderate_equivalent_min(ActivityType.SEATED_EXERCISE, None, Decimal("12"), None)
    assert result == 10.0


def test_intensity_adjusts_only_when_using_default_met() -> None:
    # met_value 없이 intensity=high면 기본 MET에 ×1.3 보정. 좌식 2.5×1.3=3.25 → 12분 → 12×3.25/3 = 13.0.
    result = moderate_equivalent_min(ActivityType.SEATED_EXERCISE, Intensity.HIGH, Decimal("12"), None)
    assert result == 13.0


def test_none_duration_is_zero() -> None:
    assert moderate_equivalent_min(ActivityType.WALKING, None, None, None) == 0.0


def test_activity_practice_flags_follow_model_weekly_definitions() -> None:
    logs = [
        SimpleNamespace(
            activity_date=date(2026, 7, day),
            activity_type=ActivityType.WALKING,
            duration_min=30,
            reps=None,
            sets=None,
        )
        for day in range(1, 6)
    ] + [
        SimpleNamespace(
            activity_date=date(2026, 7, day),
            activity_type=ActivityType.SEATED_EXERCISE,
            duration_min=10,
            reps=None,
            sets=None,
        )
        for day in (1, 3)
    ]

    walking_practice, strength_exercise = derive_activity_practice_flags(logs, activity_window_days=7)  # type: ignore[arg-type]

    assert walking_practice is True
    assert strength_exercise is True


def test_activity_practice_flags_scale_weekly_definitions_for_fourteen_days() -> None:
    logs = [
        SimpleNamespace(
            activity_date=date(2026, 7, day),
            activity_type=ActivityType.WALKING,
            duration_min=30,
            reps=None,
            sets=None,
        )
        for day in range(1, 10)
    ] + [
        SimpleNamespace(
            activity_date=date(2026, 7, day),
            activity_type=ActivityType.STANDING_EXERCISE,
            duration_min=None,
            reps=10,
            sets=1,
        )
        for day in (1, 3, 8, 10)
    ]

    walking_practice, strength_exercise = derive_activity_practice_flags(logs, activity_window_days=14)  # type: ignore[arg-type]

    assert walking_practice is False
    assert strength_exercise is True

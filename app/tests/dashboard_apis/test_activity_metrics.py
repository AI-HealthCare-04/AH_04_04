# =====================================================================================
# 활동량 환산 헬퍼(app/services/activity_metrics) 단위 테스트.
# 확정 공식(지영님 합의): moderate_equivalent_min = duration_min × (effective_MET / 3.0)
#   1) met_value 있으면 그대로  2) 없으면 activity_type 기본 MET
#   3) 기본 MET를 쓴 경우에만 intensity 보정  4) duration 없으면 0
# =====================================================================================
from decimal import Decimal

from app.models.enums import ActivityType, Intensity
from app.services.activity_metrics import moderate_equivalent_min


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

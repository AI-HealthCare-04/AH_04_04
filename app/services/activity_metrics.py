# =====================================================================================
# 활동량 환산 — 신체활동 로그를 "중강도 상당 분(moderate-equivalent minutes)"으로 환산한다.
#
# 확정 공식(지영님 합의, 2026-07-10): 중강도 = 3 MET 기준.
#   moderate_equivalent_min = duration_min × (effective_MET / 3.0)
#   effective_MET 결정:
#     1) 로그에 met_value가 있으면 그대로 사용(운동은 클라가 보냄)
#     2) 없으면 activity_type 기본 MET 사용(걷기는 구조상 met_value가 없어 항상 여기로)
#     3) 기본 MET를 쓴 경우에만 intensity 보정(met_value가 있으면 보정하지 않음)
#   duration_min이 없으면 0으로 본다.
#
# 현재는 대시보드 조회 시점(read-time)에만 쓰지만, 향후 저장 시점 계산으로 옮길 수 있도록
#   공식을 상수/헬퍼로 분리해 둔다(미션/센서 도메인에서도 재사용 가능).
# 표시·동기부여용 지표이며 임상 수치는 아니다. 계수는 MET 표준(Compendium) 근사값.
# =====================================================================================
from collections import defaultdict
from collections.abc import Iterable
from datetime import date
from decimal import Decimal

from app.models.enums import ActivityType, Intensity
from app.models.missions import PhysicalActivityLog

# 중강도 기준 MET. 이 값으로 나눠 "중강도 상당 분"으로 정규화한다.
MODERATE_MET_BASELINE = 3.0

# activity_type별 기본 MET (met_value가 없을 때 사용).
DEFAULT_MET: dict[ActivityType, float] = {
    ActivityType.WALKING: 3.0,
    ActivityType.CHAIR_STAND: 3.5,
    ActivityType.STANDING_EXERCISE: 3.5,
    ActivityType.SEATED_EXERCISE: 2.5,
    ActivityType.STRETCHING: 2.3,
}
# activity_type이 표에 없을 때의 보수적 기본값(중강도로 간주).
FALLBACK_MET = 3.0

# intensity 보정 계수 (기본 MET를 쓴 경우에만 적용).
INTENSITY_FACTOR: dict[Intensity, float] = {
    Intensity.LOW: 0.8,
    Intensity.MODERATE: 1.0,
    Intensity.HIGH: 1.3,
}

# Model feature meanings: pa_walk_30min_5days / pa_muscle_2days.
# A 14-day window applies the same weekly standard twice.
WALKING_MINUTES_PER_DAY = 30.0
WALKING_DAYS_PER_WEEK = 5
STRENGTH_DAYS_PER_WEEK = 2
_STRENGTH_ACTIVITY_TYPES = frozenset(
    {
        ActivityType.CHAIR_STAND,
        ActivityType.SEATED_EXERCISE,
        ActivityType.STANDING_EXERCISE,
    }
)


def moderate_equivalent_min(
    activity_type: ActivityType,
    intensity: Intensity | None,
    duration_min: Decimal | float | None,
    met_value: Decimal | float | None,
) -> float:
    """신체활동 로그 1건을 중강도 상당 분으로 환산한다(소수 1자리 반올림)."""
    if duration_min is None:
        return 0.0

    if met_value is not None:
        effective_met = float(met_value)
    else:
        effective_met = DEFAULT_MET.get(activity_type, FALLBACK_MET)
        if intensity is not None:
            effective_met *= INTENSITY_FACTOR.get(intensity, 1.0)

    return round(float(duration_min) * effective_met / MODERATE_MET_BASELINE, 1)


def derive_activity_practice_flags(
    logs: Iterable[PhysicalActivityLog],
    *,
    activity_window_days: int,
) -> tuple[bool, bool]:
    """Convert service logs into the model's walking and strength features."""
    if activity_window_days not in (7, 14):
        raise ValueError("activity_window_days must be 7 or 14.")
    weeks = activity_window_days // 7

    walking_minutes_by_day: defaultdict[date, float] = defaultdict(float)
    strength_days: set[date] = set()
    for log in logs:
        if log.activity_type == ActivityType.WALKING:
            walking_minutes_by_day[log.activity_date] += float(log.duration_min or 0)
        elif log.activity_type in _STRENGTH_ACTIVITY_TYPES and (
            log.duration_min is not None or log.reps is not None or log.sets is not None
        ):
            strength_days.add(log.activity_date)

    walking_days = sum(
        minutes >= WALKING_MINUTES_PER_DAY for minutes in walking_minutes_by_day.values()
    )
    return (
        walking_days >= WALKING_DAYS_PER_WEEK * weeks,
        len(strength_days) >= STRENGTH_DAYS_PER_WEEK * weeks,
    )

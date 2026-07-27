from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, timedelta


@dataclass(frozen=True)
class CurrentStreak:
    current_days: int
    completed_today: bool


def compute_current_streak(completed_dates: Iterable[date], *, as_of_date: date) -> CurrentStreak:
    """성공한 미션 날짜로 KST 기준 현재 스트릭을 계산한다.

    오늘 아직 미션을 완료하지 않았더라도 어제까지 이어진 스트릭은 오늘이
    끝나기 전까지 유지한다. 가장 최근 성공일이 그보다 오래됐으면 스트릭은 0이다.
    """
    completed = {day for day in completed_dates if day <= as_of_date}
    completed_today = as_of_date in completed
    cursor = as_of_date if completed_today else as_of_date - timedelta(days=1)

    if cursor not in completed:
        return CurrentStreak(current_days=0, completed_today=completed_today)

    current_days = 0
    while cursor in completed:
        current_days += 1
        cursor -= timedelta(days=1)

    return CurrentStreak(current_days=current_days, completed_today=completed_today)

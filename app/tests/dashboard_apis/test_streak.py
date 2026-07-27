from datetime import date, timedelta

import pytest

from app.services.streak import CurrentStreak, compute_current_streak

TODAY = date(2026, 7, 27)


@pytest.mark.parametrize(
    ("completed_dates", "expected"),
    [
        ([], CurrentStreak(current_days=0, completed_today=False)),
        ([TODAY], CurrentStreak(current_days=1, completed_today=True)),
        (
            [TODAY, TODAY - timedelta(days=1), TODAY - timedelta(days=2)],
            CurrentStreak(current_days=3, completed_today=True),
        ),
        (
            [TODAY - timedelta(days=1), TODAY - timedelta(days=2)],
            CurrentStreak(current_days=2, completed_today=False),
        ),
        ([TODAY - timedelta(days=2)], CurrentStreak(current_days=0, completed_today=False)),
        (
            [TODAY, TODAY - timedelta(days=2)],
            CurrentStreak(current_days=1, completed_today=True),
        ),
    ],
)
def test_compute_current_streak(completed_dates: list[date], expected: CurrentStreak) -> None:
    assert compute_current_streak(completed_dates, as_of_date=TODAY) == expected


def test_compute_current_streak_ignores_duplicates_and_future_dates() -> None:
    assert compute_current_streak(
        [TODAY, TODAY, TODAY + timedelta(days=1), TODAY - timedelta(days=1)],
        as_of_date=TODAY,
    ) == CurrentStreak(current_days=2, completed_today=True)

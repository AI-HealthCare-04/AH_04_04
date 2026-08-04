# =====================================================================================
# 시각/날짜 헬퍼 — 서비스의 "오늘"은 KST(Asia/Seoul) 기준으로 통일한다(팀 확정).
#   - Python 코드에서 `date.today()`(프로세스 OS tz) 직접 사용 금지 → `today_kst()` 사용.
#   - DB 쪽 `func.current_date()`는 MySQL 세션 tz를 +09:00으로 고정(app/core/db/session.py)해
#     KST 오늘로 정합시킨다. 두 경로가 같은 KST 기준을 보게 하는 게 목적.
# =====================================================================================
from datetime import date, datetime

from app.core import config


def now_kst() -> datetime:
    """현재 시각(KST, tz-aware)."""
    return datetime.now(config.TIMEZONE)


def today_kst() -> date:
    """오늘 날짜(KST 기준)."""
    return now_kst().date()


def to_kst_date(value: datetime) -> date:
    """저장된 시각을 KST 날짜로 바꾼다(#422 — 온보딩 완료일 판정).

    naive 로 돌아오는 값은 이미 KST 다(세션 tz 를 +09:00 으로 고정해 두었다). aware 면 KST 로
    변환한 뒤 날짜를 뗀다 — 드라이버·백엔드에 따라 UTC aware 로 오는 경우가 있어, 그대로
    `.date()` 하면 자정 근처에서 하루가 밀린다.
    """
    if value.tzinfo is None:
        return value.date()
    return value.astimezone(config.TIMEZONE).date()

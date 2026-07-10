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

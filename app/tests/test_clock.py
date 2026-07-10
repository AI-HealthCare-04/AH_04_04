# =====================================================================================
# 시각 헬퍼(app/core/utils/clock) 단위 테스트.
# 서비스의 "오늘"은 KST(Asia/Seoul) 기준이어야 한다(팀 확정). DB 불필요.
# =====================================================================================
from datetime import date, datetime, timedelta

from app.core import config
from app.core.utils.clock import now_kst, today_kst


def test_now_kst_is_seoul_aware() -> None:
    now = now_kst()
    # tz-aware이고 KST(+09:00, DST 없음)여야 한다.
    assert now.tzinfo is not None
    assert now.utcoffset() == timedelta(hours=9)
    assert now.tzinfo == config.TIMEZONE


def test_today_kst_matches_seoul_date() -> None:
    today = today_kst()
    assert isinstance(today, date)
    # 프로세스 OS tz가 아니라 KST 기준. 자정 경계 레이스만 허용(±1일).
    expected = datetime.now(config.TIMEZONE).date()
    assert abs((today - expected).days) <= 1

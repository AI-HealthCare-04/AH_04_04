# =====================================================================================
# KstDatetime 직렬화 단위 테스트.
# 응답 타임스탬프는 입력이 어떤 tz든 항상 KST 오프셋(+09:00)으로 나가야 한다(v7.8).
#   - naive          : KST 벽시계 값으로 보고 +09:00 부착
#   - UTC aware       : astimezone으로 KST로 변환(값도 +9h 이동)
#   - 이미 KST aware  : 그대로
# =====================================================================================
from datetime import UTC, datetime

from pydantic import BaseModel

from app.core import config
from app.dtos.base import KstDatetime


class _Model(BaseModel):
    at: KstDatetime


def _serialized(value: datetime) -> str:
    return _Model(at=value).model_dump()["at"]


def test_naive_gets_kst_offset() -> None:
    # naive는 KST 벽시계 값으로 보고 +09:00만 부착(시각 이동 없음).
    assert _serialized(datetime(2026, 7, 13, 9, 15, 0)) == "2026-07-13T09:15:00+09:00"


def test_utc_aware_is_normalized_to_kst() -> None:
    # UTC 00:15 → KST 09:15 (+9h)로 변환되어야 한다.
    assert _serialized(datetime(2026, 7, 13, 0, 15, 0, tzinfo=UTC)) == "2026-07-13T09:15:00+09:00"


def test_kst_aware_stays_kst() -> None:
    # 이미 KST aware면 값·오프셋 그대로.
    assert _serialized(datetime(2026, 7, 13, 9, 15, 0, tzinfo=config.TIMEZONE)) == "2026-07-13T09:15:00+09:00"

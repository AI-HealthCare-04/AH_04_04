# =====================================================================================
# MySQL 세션 타임존 통합 테스트 — 실제 DB 연결이 필요하다.
#
# 검증 항목(지영님 요청):
#   1) 연결(세션)마다 `SET time_zone='+09:00'`이 적용되는지
#   2) `SELECT CURDATE()`가 KST 오늘로 나오는지 (Python today_kst()와 일치)
#   3) 새 연결에서도 매번 KST가 유지되는지 (풀에서 새 연결을 받아도 동일)
#
# CI(pytest)는 DB 비의존이라 기본 스킵. 로컬 실행:
#   DB_INTEGRATION=1 DB_HOST=127.0.0.1 DB_USER=ozcoding DB_PASSWORD=pw1234 DB_NAME=ai_health \
#     uv run --group app --group dev pytest app/tests/test_db_timezone.py
#
# 주의: 하나의 이벤트 루프 안에서 검증하고 끝에 engine.dispose()로 풀을 정리한다
#       (pytest-asyncio는 테스트마다 새 루프를 만들어, 모듈 엔진 풀을 재사용하면 loop 충돌).
# =====================================================================================
import os

import pytest
from sqlalchemy import text

from app.core.utils.clock import today_kst

pytestmark = pytest.mark.skipif(
    not os.getenv("DB_INTEGRATION"),
    reason="실제 MySQL 연결 필요 (로컬: DB_INTEGRATION=1 + Docker MySQL). CI 기본 스킵.",
)


async def test_session_timezone_is_kst_on_every_connection() -> None:
    from app.core.db.session import engine

    try:
        # 새 연결을 여러 번 받아도 매번 KST(+09:00)가 걸려 있고, CURDATE()가 KST 오늘이어야 한다.
        for _ in range(3):
            async with engine.connect() as conn:
                session_tz = (await conn.execute(text("SELECT @@session.time_zone"))).scalar()
                assert session_tz == "+09:00"

                curdate = (await conn.execute(text("SELECT CURDATE()"))).scalar()
                assert curdate == today_kst()
    finally:
        await engine.dispose()

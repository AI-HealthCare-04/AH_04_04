# =====================================================================================
# 테스트 공용 픽스처.
#
# client    : DB가 필요 없는 테스트용(인증 가드 401/422 등). 앱만 띄운다.
# db_client : 실제 MySQL(운영과 동일 엔진)에 연결된 통합 테스트용.
#   - 운영이 MySQL이라 통합 테스트도 MySQL로 돌려 KST 세션 tz·upsert·enum 등
#     MySQL 특화 동작까지 검증한다(SQLite는 이 부분을 못 잡음).
#   - MySQL이 없으면(예: 현재 CI) 해당 테스트만 스킵한다. no-DB 테스트는 영향 없음.
#   - 접속 정보는 TEST_DB_* 환경변수로 덮어쓸 수 있고, 기본값은 로컬 Docker MySQL이다.
#   - 매 테스트 전 전체 테이블을 비워 격리한다.
# =====================================================================================
import os
from collections.abc import AsyncGenerator
from typing import Any

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import URL, event, text
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

import app.models as _register_models  # noqa: F401  (모든 모델을 Base.metadata에 등록)
from app.core.db.session import get_db_session
from app.main import app
from app.models.base import Base

_TEST_DB_NAME = os.getenv("TEST_DB_NAME", "test_ah0404")


def _mysql_url(database: str | None) -> URL:
    return URL.create(
        "mysql+asyncmy",
        username=os.getenv("TEST_DB_USER", "root"),
        # 비밀번호는 하드코딩하지 않는다. 미설정 시 접속이 실패해 통합 테스트를 스킵(또는 CI에선 실패)한다.
        password=os.getenv("TEST_DB_PASSWORD"),
        host=os.getenv("TEST_DB_HOST", "127.0.0.1"),
        port=int(os.getenv("TEST_DB_PORT", "3306")),
        database=database,
        query={"charset": "utf8mb4"},
    )


def _skip_or_fail_without_mysql(reason: str) -> None:
    # TEST_DB_REQUIRED가 설정되면(=CI 파이프라인) 스킵이 아니라 실패시킨다.
    #   → "CI 초록 = 통합 테스트 실제 실행"을 보장(MySQL 없으면 초록이 '스킵'이 되는 함정 방지).
    #   ci.yml에서 MySQL service와 함께 TEST_DB_REQUIRED=1을 켠다. 로컬은 미설정이라 스킵.
    #   (범용 CI 환경변수는 로컬 셸에도 설정돼 있을 수 있어 명시적 플래그를 쓴다.)
    if os.getenv("TEST_DB_REQUIRED"):
        pytest.fail(f"TEST_DB_REQUIRED인데 MySQL 미가용 → 통합 테스트가 실행되지 못했습니다: {reason}")
    pytest.skip(f"MySQL 미가용 → 통합 테스트 스킵: {reason}")


@pytest_asyncio.fixture(scope="session")
async def _mysql_engine() -> AsyncGenerator[AsyncEngine]:
    # 관리 연결로 테스트 DB를 만든다. 연결이 안 되면(=MySQL 미가용) 통합 테스트를 스킵한다.
    admin = create_async_engine(
        _mysql_url(None),
        isolation_level="AUTOCOMMIT",
        poolclass=NullPool,
        connect_args={"connect_timeout": 5},  # MySQL 미가용 시 빠르게 실패
    )
    try:
        async with admin.connect() as conn:
            await conn.execute(text(f"CREATE DATABASE IF NOT EXISTS {_TEST_DB_NAME}"))
    except Exception as exc:  # noqa: BLE001  (어떤 연결 오류든 MySQL 미가용으로 간주)
        _skip_or_fail_without_mysql(type(exc).__name__)
    finally:
        await admin.dispose()

    # NullPool: 연결을 캐싱하지 않아 pytest-asyncio의 함수별 이벤트 루프와 충돌하지 않는다.
    engine = create_async_engine(_mysql_url(_TEST_DB_NAME), poolclass=NullPool)

    @event.listens_for(engine.sync_engine, "connect")
    def _set_session_timezone(dbapi_connection: Any, connection_record: Any) -> None:
        # 운영과 동일하게 KST 고정(날짜 경계 동작까지 동일 조건으로 검증).
        cursor = dbapi_connection.cursor()
        try:
            cursor.execute("SET time_zone = '+09:00'")
        finally:
            cursor.close()

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    try:
        yield engine
    finally:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.drop_all)
        await engine.dispose()


@pytest_asyncio.fixture
async def db_client(_mysql_engine: AsyncEngine) -> AsyncGenerator[AsyncClient]:
    # 각 테스트 전 전체 테이블을 비운다(FK 무시하고 TRUNCATE → 격리).
    async with _mysql_engine.begin() as conn:
        await conn.execute(text("SET FOREIGN_KEY_CHECKS = 0"))
        for table in Base.metadata.sorted_tables:
            await conn.execute(text(f"TRUNCATE TABLE {table.name}"))
        await conn.execute(text("SET FOREIGN_KEY_CHECKS = 1"))

    session_factory = async_sessionmaker(_mysql_engine, expire_on_commit=False, autoflush=False)

    async def override_get_db_session() -> AsyncGenerator[AsyncSession]:
        async with session_factory() as session:
            yield session

    app.dependency_overrides[get_db_session] = override_get_db_session
    try:
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as test_client:
            yield test_client
    finally:
        app.dependency_overrides.pop(get_db_session, None)


@pytest_asyncio.fixture
async def db_sessionmaker(_mysql_engine: AsyncEngine) -> async_sessionmaker[AsyncSession]:
    # repo 수준 통합 테스트용 세션 팩토리. 세션을 직접 yield하지 않고 팩토리를 돌려주는 이유:
    # async-gen fixture의 teardown은 pytest-asyncio가 별도 루프에서 돌려서, 열린 세션을
    # teardown에서 닫으면 "다른 루프" 에러가 난다. 팩토리를 주면 세션 open/close가 테스트
    # 본문(같은 루프) 안에서 끝나 이 문제가 없다. 셋업에서 매 테스트 전 전체 테이블을 비운다.
    async with _mysql_engine.begin() as conn:
        await conn.execute(text("SET FOREIGN_KEY_CHECKS = 0"))
        for table in Base.metadata.sorted_tables:
            await conn.execute(text(f"TRUNCATE TABLE {table.name}"))
        await conn.execute(text("SET FOREIGN_KEY_CHECKS = 1"))

    return async_sessionmaker(_mysql_engine, expire_on_commit=False, autoflush=False)


@pytest_asyncio.fixture
async def client() -> AsyncGenerator[AsyncClient]:
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as test_client:
        yield test_client

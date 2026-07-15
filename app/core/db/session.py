from collections.abc import AsyncGenerator
from typing import Any

from sqlalchemy import URL, event
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core import config


def build_database_url() -> URL:
    # URL.create가 자격증명을 구조화 필드로 담아 escape를 내부 처리한다.
    #   → 비밀번호에 @ : / ? # ! 같은 URL 특수문자가 있어도 연결 URL이 깨지지 않는다.
    #     (f-string 수동 조립은 이런 문자를 escape하지 못해 host가 어긋났음.)
    return URL.create(
        "mysql+asyncmy",
        username=config.DB_USER,
        password=config.DB_PASSWORD,
        host=config.DB_HOST,
        port=config.DB_PORT,
        database=config.DB_NAME,
        query={"charset": "utf8mb4"},
    )


engine = create_async_engine(
    build_database_url(),
    pool_pre_ping=True,
    pool_recycle=3600,
    # READ COMMITTED: 요청 트랜잭션 내 읽기가 최신 커밋을 보게 한다. (MySQL 기본 REPEATABLE READ는
    #   요청 첫 읽기(인증)에 스냅샷을 고정해, 완료 직렬화 잠금 이후에도 동시 커밋을 못 봐서 걷기 하루
    #   1회 판정·요약 재집계가 어긋난다. 잠금(FOR UPDATE)과 결합해 동시 완료를 정확히 처리한다.)
    isolation_level="READ COMMITTED",
)


@event.listens_for(engine.sync_engine, "connect")
def _set_session_timezone(dbapi_connection: Any, connection_record: Any) -> None:
    # 모든 새 연결(=세션)마다 MySQL 세션 타임존을 KST(+09:00)로 고정한다.
    #   → func.current_date()/func.now()가 KST 기준으로 동작하고,
    #     dashboard 읽기와 mission 쓰기가 같은 KST 오늘을 보게 된다(팀 확정, 시계 통일).
    #   KST는 DST가 없어 고정 오프셋 +09:00으로 충분하다(tz 테이블 불필요).
    cursor = dbapi_connection.cursor()
    try:
        cursor.execute("SET time_zone = '+09:00'")
    finally:
        cursor.close()

AsyncSessionLocal = async_sessionmaker(
    bind=engine,
    expire_on_commit=False,
    autoflush=False,
    autocommit=False,
)


async def get_db_session() -> AsyncGenerator[AsyncSession]:
    async with AsyncSessionLocal() as session:
        yield session

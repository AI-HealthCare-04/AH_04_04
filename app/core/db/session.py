from collections.abc import AsyncGenerator
from typing import Any

from sqlalchemy import event
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core import config


def build_database_url() -> str:
    return (
        f"mysql+asyncmy://{config.DB_USER}:{config.DB_PASSWORD}"
        f"@{config.DB_HOST}:{config.DB_PORT}/{config.DB_NAME}?charset=utf8mb4"
    )


engine = create_async_engine(
    build_database_url(),
    pool_pre_ping=True,
    pool_recycle=3600,
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

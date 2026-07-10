from collections.abc import AsyncGenerator

from sqlalchemy import URL
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
)

AsyncSessionLocal = async_sessionmaker(
    bind=engine,
    expire_on_commit=False,
    autoflush=False,
    autocommit=False,
)


async def get_db_session() -> AsyncGenerator[AsyncSession]:
    async with AsyncSessionLocal() as session:
        yield session

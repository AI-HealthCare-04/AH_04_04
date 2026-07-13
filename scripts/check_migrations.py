#!/usr/bin/env python
"""마이그레이션 왕복 + drift 검사 (mysql CLI 불필요).

DB 생성/삭제는 프로젝트가 쓰는 asyncmy로, alembic은 subprocess로 실행한다
(env.py가 asyncio.run이라 async 중첩을 피하려 별도 프로세스로 호출).

⚠️ 전용 임시 DB에서만 돈다. env.py가 DB_NAME으로 URL을 만들므로 DB_NAME을 임시 DB로
   override 해 dev DB(ai_health)를 격리한다.

필요한 env: DB_HOST DB_PORT DB_USER DB_PASSWORD  (CREATE/DROP DATABASE 권한 = root 권장)
실행(저장소 루트):  uv run --group app --group dev python scripts/check_migrations.py
"""
import asyncio
import os
import subprocess
import sys

from sqlalchemy import URL, text
from sqlalchemy.ext.asyncio import create_async_engine

HOST = os.environ.get("DB_HOST", "127.0.0.1")
PORT = int(os.environ.get("DB_PORT", "3306"))
USER = os.environ.get("DB_USER", "root")
PASSWORD = os.environ.get("DB_PASSWORD")
MIG_DB = os.environ.get("MIG_DB", "test_ah0404_mig")

if not PASSWORD:
    sys.exit("DB_PASSWORD 가 필요합니다 (CREATE DATABASE 권한 있는 유저, root 권장)")


def _admin_url() -> URL:
    # database 없이 서버에만 접속(관리용). URL.create가 특수문자 escape를 처리.
    return URL.create(
        "mysql+asyncmy", username=USER, password=PASSWORD,
        host=HOST, port=PORT, database=None, query={"charset": "utf8mb4"},
    )


async def _exec(sql: str) -> None:
    engine = create_async_engine(_admin_url(), isolation_level="AUTOCOMMIT")
    try:
        async with engine.connect() as conn:
            await conn.execute(text(sql))
    finally:
        await engine.dispose()


def _alembic(*args: str) -> None:
    env = {**os.environ, "DB_NAME": MIG_DB}  # env.py가 이 임시 DB를 보게 override
    print(f"\n== alembic {' '.join(args)} ==", flush=True)
    subprocess.run(
        ["uv", "run", "--group", "app", "--group", "dev", "alembic", *args],
        check=True, env=env,
    )


def main() -> None:
    print(f"임시 DB 재생성: {MIG_DB} @ {HOST}:{PORT} (user={USER})")
    asyncio.run(_exec(f"DROP DATABASE IF EXISTS `{MIG_DB}`"))
    asyncio.run(_exec(f"CREATE DATABASE `{MIG_DB}` CHARACTER SET utf8mb4"))
    try:
        _alembic("upgrade", "head")
        _alembic("downgrade", "base")
        _alembic("upgrade", "head")
        _alembic("check")
        print("\n== OK: 마이그레이션 왕복 + drift 통과 ==")
    finally:
        asyncio.run(_exec(f"DROP DATABASE IF EXISTS `{MIG_DB}`"))


if __name__ == "__main__":
    main()

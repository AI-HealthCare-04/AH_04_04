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


def _mig_url() -> URL:
    # 임시 마이그레이션 DB에 직접 접속(가드 fixture 주입/확인용).
    return URL.create(
        "mysql+asyncmy", username=USER, password=PASSWORD,
        host=HOST, port=PORT, database=MIG_DB, query={"charset": "utf8mb4"},
    )


async def _exec(sql: str) -> None:
    engine = create_async_engine(_admin_url(), isolation_level="AUTOCOMMIT")
    try:
        async with engine.connect() as conn:
            await conn.execute(text(sql))
    finally:
        await engine.dispose()


async def _exec_many_on_mig(sqls: list[str]) -> None:
    # 한 연결에서 순차 실행(SET FOREIGN_KEY_CHECKS 세션값이 INSERT까지 유지되도록).
    engine = create_async_engine(_mig_url(), isolation_level="AUTOCOMMIT")
    try:
        async with engine.connect() as conn:
            for sql in sqls:
                await conn.execute(text(sql))
    finally:
        await engine.dispose()


async def _scalar_on_mig(sql: str) -> int:
    engine = create_async_engine(_mig_url(), isolation_level="AUTOCOMMIT")
    try:
        async with engine.connect() as conn:
            return int((await conn.execute(text(sql))).scalar_one())
    finally:
        await engine.dispose()


def _alembic(*args: str) -> None:
    env = {**os.environ, "DB_NAME": MIG_DB}  # env.py가 이 임시 DB를 보게 override
    print(f"\n== alembic {' '.join(args)} ==", flush=True)
    subprocess.run(
        ["uv", "run", "--group", "app", "--group", "dev", "alembic", *args],
        check=True, env=env,
    )


def _alembic_capture(*args: str) -> subprocess.CompletedProcess[str]:
    # 실패를 '기대'하는 호출용(check=False). 반환코드/출력을 직접 검사한다.
    env = {**os.environ, "DB_NAME": MIG_DB}
    print(f"\n== alembic {' '.join(args)} (실패 기대) ==", flush=True)
    return subprocess.run(
        ["uv", "run", "--group", "app", "--group", "dev", "alembic", *args],
        env=env, capture_output=True, text=True,
    )


def check_migration_roundtrip() -> None:
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


def check_voice_data_guard() -> None:
    # 0006의 파괴적 제거가 '음성 데이터가 실재할 때' 조용히 정규화·삭제하지 않고
    #   안전하게 중단되는지 검증한다(리뷰 #111). 왕복/drift는 스키마만 보므로 이 손실 경로를 못 잡는다.
    print(f"\n임시 DB 재생성(음성 데이터 가드 검증): {MIG_DB}", flush=True)
    asyncio.run(_exec(f"DROP DATABASE IF EXISTS `{MIG_DB}`"))
    asyncio.run(_exec(f"CREATE DATABASE `{MIG_DB}` CHARACTER SET utf8mb4"))
    try:
        # voice 컬럼/enum이 살아있는 직전 리비전까지만 올린다.
        _alembic("upgrade", "0005_backfill_kidney_check")
        # 음성 잔재 1건 시뮬레이션(FK 무시, raw_transcript+voice 세션 삽입).
        asyncio.run(
            _exec_many_on_mig(
                [
                    "SET FOREIGN_KEY_CHECKS=0",
                    "INSERT INTO health_check_sessions "
                    "(user_id,status,input_method,raw_transcript,has_estimated_value,created_at) "
                    "VALUES (1,'started','voice','백육십',0,NOW())",
                    "SET FOREIGN_KEY_CHECKS=1",
                ]
            )
        )
        # 이제 0006으로 올리면 가드가 걸려 '실패'해야 한다.
        result = _alembic_capture("upgrade", "head")
        if result.returncode == 0:
            sys.exit("가드 검증 실패: 음성 데이터가 있는데 마이그레이션이 성공했다(데이터 손실 경로!)")
        # 실패 '원인'이 preflight 가드인지까지 확인한다(리뷰 #111 비블로킹 제안).
        #   단순 non-zero만 보면, 향후 가드가 사라져도 enum ALTER 실패로 우연히 통과할 수 있다.
        guard_marker = "0006_remove_voice_parser 중단"
        combined = (result.stderr or "") + (result.stdout or "")
        if guard_marker not in combined:
            sys.exit(
                "가드 검증 실패: 마이그레이션이 실패했으나 preflight 가드가 아닌 다른 원인일 수 있음 "
                f"(가드 메시지 '{guard_marker}' 미검출). 마지막 출력:\n{combined[-2000:]}"
            )
        # 데이터가 정규화/삭제되지 않고 원본 그대로 남아있는지 확인.
        remaining = asyncio.run(
            _scalar_on_mig(
                "SELECT COUNT(*) FROM health_check_sessions "
                "WHERE input_method='voice' AND raw_transcript='백육십'"
            )
        )
        if remaining != 1:
            sys.exit(f"가드 검증 실패: 마이그레이션이 중단됐는데 데이터가 변형됐다(remaining={remaining})")
        print("== OK: 음성 데이터 존재 시 0006이 안전하게 중단되고 원본이 보존됨 ==")
    finally:
        asyncio.run(_exec(f"DROP DATABASE IF EXISTS `{MIG_DB}`"))


def main() -> None:
    check_migration_roundtrip()
    check_voice_data_guard()
    print("\n== ALL OK ==")


if __name__ == "__main__":
    main()

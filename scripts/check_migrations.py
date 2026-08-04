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
from pathlib import Path

from sqlalchemy import URL, text
from sqlalchemy.ext.asyncio import create_async_engine

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.db.database_name_safety import validate_ephemeral_database_name  # noqa: E402

HOST = os.environ.get("DB_HOST", "127.0.0.1")
PORT = int(os.environ.get("DB_PORT", "3306"))
USER = os.environ.get("DB_USER", "root")
PASSWORD = os.environ.get("DB_PASSWORD")
try:
    MIG_DB = validate_ephemeral_database_name(
        os.environ.get("MIG_DB", "test_ah0404_mig"),
        application_database=os.environ.get("DB_NAME"),
    )
except ValueError as exc:
    sys.exit(f"안전하지 않은 MIG_DB: {exc}")

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
    # check=False. 반환코드/출력을 호출부에서 직접 검사한다(성공·실패 기대 케이스 공용).
    env = {**os.environ, "DB_NAME": MIG_DB}
    print(f"\n== alembic {' '.join(args)} (반환코드 검사) ==", flush=True)
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


def _seed_6m_row_at_0007(values: str) -> None:
    # 0008 직전 리비전(0007_oauth_login_nonces)까지 올린 뒤, walk_6m 컬럼을 채운 평가 1건을 심는다.
    asyncio.run(_exec(f"DROP DATABASE IF EXISTS `{MIG_DB}`"))
    asyncio.run(_exec(f"CREATE DATABASE `{MIG_DB}` CHARACTER SET utf8mb4"))
    _alembic("upgrade", "0007_oauth_login_nonces")
    asyncio.run(
        _exec_many_on_mig(
            [
                "SET FOREIGN_KEY_CHECKS=0",
                "INSERT INTO physical_assessments "
                "(user_id,assessment_type,chair_stand_skipped,walk_6m_time_sec,walk_6m_skipped,"
                f"pain_reported,dizziness_reported,used_for_level_setting) VALUES {values}",
                "SET FOREIGN_KEY_CHECKS=1",
            ]
        )
    )


def check_walk_6m_data_guard() -> None:
    # 0008의 파괴적 컬럼 DROP이 '6m 측정 데이터가 실재할 때' 조용히 삭제하지 않고
    #   안전하게 중단되는지 검증한다(리뷰 #103, voice와 동일 패턴).
    print(f"\n임시 DB 재생성(6m 측정값 가드 검증): {MIG_DB}", flush=True)
    try:
        # walk_6m_time_sec를 채운 '측정값' 행 → upgrade가 가드에 걸려 실패해야 한다.
        _seed_6m_row_at_0007("(1,'initial',0,6.10,0,0,0,0)")
        result = _alembic_capture("upgrade", "head")
        if result.returncode == 0:
            sys.exit("가드 검증 실패: 6m 측정 데이터가 있는데 마이그레이션이 성공했다(데이터 손실 경로!)")
        guard_marker = "0008_drop_walk_6m_columns 중단"
        combined = (result.stderr or "") + (result.stdout or "")
        if guard_marker not in combined:
            sys.exit(
                "가드 검증 실패: 마이그레이션이 실패했으나 preflight 가드가 아닌 다른 원인일 수 있음 "
                f"(가드 메시지 '{guard_marker}' 미검출). 마지막 출력:\n{combined[-2000:]}"
            )
        remaining = asyncio.run(
            _scalar_on_mig("SELECT COUNT(*) FROM physical_assessments WHERE walk_6m_time_sec=6.10")
        )
        if remaining != 1:
            sys.exit(f"가드 검증 실패: 마이그레이션이 중단됐는데 데이터가 변형됐다(remaining={remaining})")
        print("== OK: 6m 측정값 존재 시 0008이 안전하게 중단되고 원본이 보존됨 ==")
    finally:
        asyncio.run(_exec(f"DROP DATABASE IF EXISTS `{MIG_DB}`"))


def check_walk_6m_skipped_only_drops() -> None:
    # skipped-only 행(측정값 없이 walk_6m_skipped=1)은 '측정값'이 아니라 파생 플래그라 폐기 대상(리뷰 #118-2).
    #   가드에 걸리지 않고 정상적으로 컬럼과 함께 DROP돼야 한다(중단되면 5STS 단독 평가 DB가 통째로 막힘).
    print(f"\n임시 DB 재생성(skipped-only 폐기 검증): {MIG_DB}", flush=True)
    try:
        _seed_6m_row_at_0007("(1,'initial',0,NULL,1,0,0,0)")
        result = _alembic_capture("upgrade", "head")
        if result.returncode != 0:
            sys.exit(
                "skipped-only 검증 실패: skipped=1(측정값 없음) 행에 가드가 걸려 마이그레이션이 중단됐다. "
                f"마지막 출력:\n{(result.stderr or '') + (result.stdout or '')}[-2000:]"
            )
        dropped = asyncio.run(
            _scalar_on_mig(
                "SELECT COUNT(*) FROM information_schema.columns "
                f"WHERE table_schema='{MIG_DB}' AND table_name='physical_assessments' "
                "AND column_name LIKE 'walk_6m_%'"
            )
        )
        if dropped != 0:
            sys.exit(f"skipped-only 검증 실패: walk_6m 컬럼이 남아있다(count={dropped})")
        print("== OK: skipped-only 행은 가드에 안 걸리고 컬럼과 함께 정상 폐기됨 ==")
    finally:
        asyncio.run(_exec(f"DROP DATABASE IF EXISTS `{MIG_DB}`"))


def _seed_prediction_at_0020(snapshot_sql: str, *, score_cohort_age: str = "NULL") -> None:
    """0021 직전 리비전까지 올린 뒤, 주어진 스냅샷을 가진 예측 1건을 심는다(#408 가드 검증용)."""
    asyncio.run(_exec(f"DROP DATABASE IF EXISTS `{MIG_DB}`"))
    asyncio.run(_exec(f"CREATE DATABASE `{MIG_DB}` CHARACTER SET utf8mb4"))
    _alembic("upgrade", "0020_mission_bonus")
    asyncio.run(
        _exec_many_on_mig(
            [
                "SET FOREIGN_KEY_CHECKS=0",
                "INSERT INTO risk_predictions "
                "(user_id,profile_id,model_version,model_variant,internal_risk_score,internal_risk_level,"
                "score_cohort_age,input_snapshot) VALUES "
                f"(1,1,'v1','minimal',0.12,'medium',{score_cohort_age},{snapshot_sql})",
                "SET FOREIGN_KEY_CHECKS=1",
            ]
        )
    )


def check_prediction_snapshot_guard() -> None:
    """0021이 '백필 실패를 삼키고 원본을 지우는' 경로를 막는지 검증한다(#408 리뷰 P1).

    삭제는 되돌릴 수 없다 — 조회 키를 복원하지 못한 행이 있으면 중단하고 원본을 보존해야 한다.
    누락·타입 불일치·모델 인코딩 아닌 성별을 각각 심어 확인한다.
    """
    cases = {
        "성별 키 누락": "JSON_OBJECT('age',72.0,'bmi',22.5)",
        "성별 타입 불일치": "JSON_OBJECT('age',72.0,'sex','male')",
        "모델 인코딩 아닌 성별(0)": "JSON_OBJECT('age',72.0,'sex',0)",
        "나이 키 누락": "JSON_OBJECT('sex',1,'bmi',22.5)",
    }
    for label, snapshot_sql in cases.items():
        print(f"\n임시 DB 재생성(예측 스냅샷 가드 검증 — {label}): {MIG_DB}", flush=True)
        try:
            _seed_prediction_at_0020(snapshot_sql)
            result = _alembic_capture("upgrade", "head")
            if result.returncode == 0:
                sys.exit(f"가드 검증 실패({label}): 백필이 불완전한데 마이그레이션이 성공했다(원본 삭제 경로!)")
            guard_marker = "0021_drop_input_snapshot 중단"
            combined = (result.stderr or "") + (result.stdout or "")
            if guard_marker not in combined:
                sys.exit(
                    f"가드 검증 실패({label}): 마이그레이션이 실패했으나 preflight 가드가 아닌 다른 원인일 수 "
                    f"있음 (가드 메시지 '{guard_marker}' 미검출). 마지막 출력:\n{combined[-2000:]}"
                )
            # 중단됐으면 원본이 그대로 남아 있어야 한다 — 이게 가드의 목적이다.
            remaining = asyncio.run(
                _scalar_on_mig("SELECT COUNT(*) FROM risk_predictions WHERE input_snapshot IS NOT NULL")
            )
            if remaining != 1:
                sys.exit(f"가드 검증 실패({label}): 중단됐는데 원본이 사라졌다(remaining={remaining})")
            print(f"== OK: {label} 시 0021이 중단되고 원본이 보존됨 ==")
        finally:
            asyncio.run(_exec(f"DROP DATABASE IF EXISTS `{MIG_DB}`"))


def check_prediction_snapshot_rerun_after_fix() -> None:
    """가드 실패 후 **같은 DB 에서** 데이터를 고쳐 재실행하면 성공하는지 검증한다(#410 리뷰 P1).

    MySQL 은 DDL 이 암시적 커밋이라, 컬럼을 추가한 뒤 실패하면 revision 은 0020 에 머문 채 컬럼만
    남아 재실행이 duplicate column 으로 막힌다. 안내대로 데이터를 교정해도 복구가 안 되면 가드가
    '중단'이 아니라 '막다른 길'이 된다 — 그래서 실제 복구 경로를 통째로 확인한다.
    """
    print(f"\n임시 DB 재생성(가드 실패 후 재실행 검증): {MIG_DB}", flush=True)
    try:
        # 성별이 모델 인코딩이 아닌 행(0) → 첫 실행은 가드로 중단돼야 한다.
        _seed_prediction_at_0020("JSON_OBJECT('age',72.0,'sex',0,'bmi',22.5)")
        first = _alembic_capture("upgrade", "head")
        if first.returncode == 0:
            sys.exit("재실행 검증 실패: 복원 불가 행이 있는데 첫 실행이 성공했다(원본 삭제 경로!)")

        # 안내대로 데이터를 교정한다(스냅샷의 sex 를 male=1 로).
        asyncio.run(
            _exec_many_on_mig(
                ["UPDATE risk_predictions SET input_snapshot = JSON_SET(input_snapshot, '$.sex', 1)"]
            )
        )

        # 같은 DB 에 그대로 재실행 → 이번엔 성공해야 한다(컬럼 추가가 조건부·백필이 멱등).
        second = _alembic_capture("upgrade", "head")
        if second.returncode != 0:
            sys.exit(
                "재실행 검증 실패: 데이터를 교정했는데 재실행이 실패했다(가드가 막다른 길이 된다). "
                f"마지막 출력:\n{((second.stderr or '') + (second.stdout or ''))[-2000:]}"
            )
        migrated = asyncio.run(
            _scalar_on_mig(
                "SELECT COUNT(*) FROM risk_predictions "
                "WHERE score_cohort_age='72' AND score_cohort_sex=1 AND input_snapshot IS NULL"
            )
        )
        if migrated != 1:
            sys.exit(f"재실행 검증 실패: 재실행 후 파생값 보존/원본 폐기가 기대와 다르다(matched={migrated})")
        print("== OK: 가드 중단 → 데이터 교정 → 같은 DB 재실행 성공 → 파생값 보존 후 원본 폐기 ==")
    finally:
        asyncio.run(_exec(f"DROP DATABASE IF EXISTS `{MIG_DB}`"))


def check_prediction_snapshot_backfill() -> None:
    """정상 행은 가드에 걸리지 않고 파생값 보존 후 원본이 폐기되는지 검증한다(#408).

    가드만 있으면 '항상 중단'으로도 통과하므로 반대 방향(정상 경로)도 함께 못박는다.
    나이 키 백필 규칙(80 이상 → '80+')도 여기서 확인한다.
    """
    print(f"\n임시 DB 재생성(예측 스냅샷 백필 검증): {MIG_DB}", flush=True)
    try:
        # 나이 키가 비어 있는 구행 + 80 top-coding 대상(85세) + female(2).
        _seed_prediction_at_0020("JSON_OBJECT('age',85.0,'sex',2,'bmi',22.5,'waist_cm',82.0)")
        result = _alembic_capture("upgrade", "head")
        if result.returncode != 0:
            sys.exit(
                "백필 검증 실패: 정상 스냅샷인데 마이그레이션이 중단됐다. "
                f"마지막 출력:\n{((result.stderr or '') + (result.stdout or ''))[-2000:]}"
            )
        preserved = asyncio.run(
            _scalar_on_mig(
                "SELECT COUNT(*) FROM risk_predictions "
                "WHERE score_cohort_age='80+' AND score_cohort_sex=2 AND input_snapshot IS NULL"
            )
        )
        if preserved != 1:
            sys.exit(f"백필 검증 실패: 파생값 보존/원본 폐기가 기대와 다르다(matched={preserved})")
        print("== OK: 정상 행은 파생값(80+, sex=2) 보존 후 원본이 폐기됨 ==")
    finally:
        asyncio.run(_exec(f"DROP DATABASE IF EXISTS `{MIG_DB}`"))


def _marked_reassessments() -> list[int]:
    """재평가로 표시된 예측 id 목록. GROUP_CONCAT 은 드라이버마다 반환 타입이 갈려 쓰지 않는다."""
    ids: list[int] = []
    for prediction_id in (1, 2, 3):
        flag = asyncio.run(
            _scalar_on_mig(f"SELECT is_reassessment FROM risk_predictions WHERE prediction_id = {prediction_id}")
        )
        if int(flag or 0) == 1:
            ids.append(prediction_id)
    return ids


def check_reassessment_marker_backfill() -> None:
    """0022 가 기존 예측의 재평가 여부를 프로필에서 옮겨 오는지 검증한다(#408 A+3).

    이 백필이 어긋나면 하루 1회 정책(#396)이 끊긴다 — 재평가였던 예측이 0 으로 남으면 오늘 이미
    재평가한 사용자가 한 번 더 계산할 수 있고, 반대면 온보딩 당일 재평가가 막힌다.
    재평가본(service_log)·온보딩본(form)·프로필이 사라진 고아 행을 함께 심어 확인한다.
    """
    print(f"\n임시 DB 재생성(재평가 표시 백필 검증): {MIG_DB}", flush=True)
    try:
        asyncio.run(_exec(f"DROP DATABASE IF EXISTS `{MIG_DB}`"))
        asyncio.run(_exec(f"CREATE DATABASE `{MIG_DB}` CHARACTER SET utf8mb4"))
        _alembic("upgrade", "0021_drop_input_snapshot")
        profile_cols = (
            "(profile_id,user_id,birth_date,sex,height_cm,weight_kg,bmi,walk_days,musc_days,"
            "activity_input_source,kidney_status,protein_restriction_status,protein_challenge_allowed,"
            "input_method,has_estimated_value)"
        )
        base = "'1958-03-01','male',168,63,22.3,3,2,'self_report','none','none',1"
        asyncio.run(
            _exec_many_on_mig(
                [
                    "SET FOREIGN_KEY_CHECKS=0",
                    f"INSERT INTO health_profiles {profile_cols} VALUES (1,1,{base},'form',0)",
                    f"INSERT INTO health_profiles {profile_cols} VALUES (2,1,{base},'service_log',1)",
                    "INSERT INTO risk_predictions "
                    "(prediction_id,user_id,profile_id,model_version,model_variant,"
                    "internal_risk_score,internal_risk_level) VALUES "
                    "(1,1,1,'v1','minimal',0.12,'medium'),"   # 온보딩본
                    "(2,1,2,'v1','minimal',0.13,'medium'),"   # 재평가본
                    "(3,1,999,'v1','minimal',0.14,'medium')",  # 프로필이 없는 고아 행
                    "SET FOREIGN_KEY_CHECKS=1",
                ]
            )
        )
        result = _alembic_capture("upgrade", "head")
        if result.returncode != 0:
            sys.exit(
                "재평가 표시 백필 실패: 마이그레이션이 중단됐다. "
                f"마지막 출력:\n{((result.stderr or '') + (result.stdout or ''))[-2000:]}"
            )
        marked = _marked_reassessments()
        if marked != [2]:
            sys.exit(f"재평가 표시 백필 실패: 재평가로 표시된 예측이 기대와 다르다(marked={marked})")
        print("== OK: 재평가본만 1, 온보딩본·고아 행은 0 ==")
        # 멱등: 같은 DB 에 다시 올려도 결과가 같아야 한다(DDL 암시적 커밋 이후 재실행 대비).
        _alembic("downgrade", "0021_drop_input_snapshot")
        rerun = _alembic_capture("upgrade", "head")
        if rerun.returncode != 0:
            sys.exit("재평가 표시 백필 실패: 같은 DB 재실행이 막혔다")
        marked_again = _marked_reassessments()
        if marked_again != [2]:
            sys.exit(f"재평가 표시 백필 실패: 재실행 결과가 다르다(marked={marked_again})")
        print("== OK: downgrade 후 재실행해도 같은 결과 ==")
    finally:
        asyncio.run(_exec(f"DROP DATABASE IF EXISTS `{MIG_DB}`"))


def main() -> None:
    check_migration_roundtrip()
    check_voice_data_guard()
    check_walk_6m_data_guard()
    check_walk_6m_skipped_only_drops()
    check_prediction_snapshot_guard()
    check_prediction_snapshot_rerun_after_fix()
    check_prediction_snapshot_backfill()
    check_reassessment_marker_backfill()
    print("\n== ALL OK ==")


if __name__ == "__main__":
    main()

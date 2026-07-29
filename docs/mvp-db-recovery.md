# MVP DB 마이그레이션 실패 복구

## 적용 범위

이 절차는 **내부 심사용 MVP에 보존해야 할 중요 사용자 데이터가 없을 때만** 사용한다.
DB 재생성을 수행하면 사용자, 건강 프로필, 체력검사, 미션 수행 이력, 포인트 및 일별 요약이
모두 삭제된다.

중요 데이터가 생긴 뒤에는 이 절차를 사용하지 말고, 먼저 별도의 백업·복구 정책을 수립한다.

## 정상 배포 동작

자동 배포와 `scripts/deployment.sh`는 다음 순서로 배포한다.

1. MySQL을 실행하고 healthy 상태를 기다린다.
2. 일회성 FastAPI 컨테이너에서 `alembic upgrade head`를 실행한다.
3. 멱등 mission seed를 실행한다.
4. 두 단계가 모두 성공한 경우에만 FastAPI와 나머지 서비스를 실행한다.

migration 또는 seed가 실패하면 신규 애플리케이션 기동 전에 배포가 중단된다. 실패한 상태에서
FastAPI만 먼저 실행하지 않는다.

## 실패 시 판단

다음 중 하나라면 DB 재생성 절차를 사용한다.

- migration이 중간에 실패해 실제 스키마 상태를 확신할 수 없다.
- `alembic current`가 예상 revision을 반환하지 않는다.
- 테스트 데이터만 존재하며 기존 데이터를 보존할 필요가 없다.

MySQL DDL은 완전히 트랜잭션으로 복구되지 않을 수 있다. 따라서 부분 적용 가능성이 있는 DB에
`alembic upgrade head`를 반복 실행하거나 임의로 `alembic stamp`하지 않는다.

## 재생성 절차

아래 작업은 EC2의 `~/project`에서 수행한다.

1. `.env`의 `DB_NAME`과 대상 서버를 확인한다. 운영자가 예상한 MVP DB가 아니면 중단한다.
2. FastAPI와 nginx를 중단해 재생성 중 요청이 들어오지 않게 한다.

   ```bash
   COMPOSE="docker compose --env-file .env -f infra/docker/docker-compose.prod.yml"
   $COMPOSE stop fastapi nginx
   ```

3. MySQL에 root로 접속한다. 비밀번호는 명령줄에 직접 적지 않고 프롬프트에서 입력한다.

   ```bash
   $COMPOSE exec mysql mysql -uroot -p
   ```

4. MySQL 프롬프트에서 대상 DB 이름을 다시 확인한 뒤 삭제하고 생성한다. 아래
   `ai_health`는 예시이므로 반드시 `.env`의 `DB_NAME`으로 바꾼다.

   ```sql
   SHOW DATABASES;
   DROP DATABASE `ai_health`;
   CREATE DATABASE `ai_health`
     CHARACTER SET utf8mb4
     COLLATE utf8mb4_unicode_ci;
   EXIT;
   ```

5. 최신 스키마를 생성한다.

   ```bash
   $COMPOSE run --rm --no-deps fastapi uv run --no-sync alembic upgrade head
   ```

6. mission seed를 넣는다. 이 스크립트는 멱등이므로 재실행해도 중복 생성되지 않는다.

   ```bash
   $COMPOSE run --rm --no-deps fastapi \
     uv run --no-sync python -m scripts.seed_mission_templates
   ```

7. revision과 애플리케이션 테이블 수를 확인한다.

   ```bash
   $COMPOSE run --rm --no-deps fastapi uv run --no-sync alembic current
   $COMPOSE exec mysql mysql -uroot -p
   ```

   ```sql
   SELECT COUNT(*) AS app_table_count
     FROM information_schema.tables
    WHERE table_schema = 'ai_health'
      AND table_name <> 'alembic_version';
   EXIT;
   ```

   `ai_health`는 실제 `DB_NAME`으로 바꾼다.

8. 서비스를 다시 실행한다.

   ```bash
   $COMPOSE up -d
   ```

9. `/health`, 게스트 로그인, 미션 목록 조회를 smoke test한다.

## 롤백 범위

내부 심사용 MVP에서는 migration 실패 복구 수단으로 `alembic downgrade`를 사용하지 않는다.
보존 대상 데이터가 없으므로 **DB 재생성 → migration → seed → smoke test**를 표준 복구 경로로
사용한다.

재생성 후에도 같은 migration이 실패하면 DB를 반복 삭제하지 말고 배포를 중단한 뒤 migration
로그와 해당 revision 코드를 수정한다.

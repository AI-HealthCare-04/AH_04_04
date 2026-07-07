# AH_04_04

시니어 생활습관 챌린지 서비스 MVP입니다. 현재 개발 기준은 FastAPI, SQLAlchemy, MySQL, Docker Compose입니다.

## 팀 공통 개발 환경

- Docker Desktop
- uv
- Git
- Python 3.13은 로컬 실행용입니다. Docker 실행만 할 때는 로컬 Python 버전 차이가 거의 영향을 주지 않습니다.

팀 구성은 맥북 2명, 윈도우 1명을 기준으로 합니다. 맥북 팀원은 `docker compose`를 기본으로 쓰고, 윈도우에서 Compose v2가 잡히지 않으면 `docker-compose`를 씁니다.

## 최초 설정

맥북:

```bash
cp envs/example.local.env .env
docker compose up --build
```

윈도우 PowerShell:

```powershell
copy envs\example.local.env .env
docker-compose up --build
```

접속 주소:

- FastAPI: http://localhost:8000
- Swagger UI: http://localhost:8000/api/docs
- Health check: http://localhost:8000/health
- MySQL: localhost:3306

로컬에 이미 MySQL이 있어서 `3306` 포트가 충돌하면 각자 `.env`에서만 아래 값을 바꿉니다.

```env
DB_EXPOSE_PORT=3307
```

컨테이너 내부 DB 포트와 앱 연결은 계속 `DB_HOST=mysql`, `DB_PORT=3306`을 사용합니다.

## Nginx 선택 실행

개발 기본 실행에는 Nginx를 포함하지 않습니다. 포트 80 충돌을 줄이기 위해서입니다.

Nginx까지 같이 확인해야 할 때:

```bash
docker compose -f docker-compose.yml -f docker-compose.nginx.yml up --build
```

윈도우 PowerShell:

```powershell
docker-compose -f docker-compose.yml -f docker-compose.nginx.yml up --build
```

포트 80이 충돌하면 `.env`에서 바꿉니다.

```env
NGINX_EXPOSE_PORT=8080
```

## 로컬 uv 실행

Docker가 아니라 로컬 가상환경에서 실행할 때:

```bash
uv sync --group app --group dev
uv run uvicorn app.main:app --reload
```

이 경우 DB는 Docker MySQL만 따로 띄워도 됩니다.

```bash
docker compose up mysql
```

## 검증 명령

```bash
uv run pytest
uv run ruff check app
```

윈도우에서 uv 캐시 권한 문제가 나면 터미널을 새로 열거나 관리자 권한 PowerShell에서 다시 실행합니다.

## 현재 MVP 범위

포함:

- FastAPI
- Pydantic
- SQLAlchemy async
- MySQL
- Alembic
- Docker Compose
- Android 앱 연동을 위한 REST API 구조

뒤로 미룸:

- Redis
- Celery
- 별도 ai_worker
- Room DB
- RDS / Aiven / ECS

## 백엔드 구조

```text
app/
├── apis/v1/          # API router
├── core/             # config, db, jwt, utils
├── dependencies/     # FastAPI dependencies
├── dtos/             # Pydantic request/response
├── ml/               # MVP 내부 예측 모듈
├── models/           # SQLAlchemy ORM
├── repositories/     # DB access
├── services/         # business logic
├── tests/
└── main.py
```

API 명세는 `/api/v1` 아래에 맞춥니다. 수행 가능한 미션 목록은 `GET /api/v1/missions?status=available`을 우선합니다.

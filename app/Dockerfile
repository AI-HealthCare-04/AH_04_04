FROM python:3.13-slim

WORKDIR /app

ENV UV_LINK_MODE=copy
ENV UV_COMPILE_BYTECODE=1

RUN apt-get update && apt-get install -y \
    build-essential \
    python3-dev \
    libffi-dev \
    && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:latest /uv /bin/uv

COPY pyproject.toml uv.lock ./
RUN uv sync --group app --no-dev --frozen --no-install-project

COPY ./app ./app
# alembic.ini(마이그레이션 설정)를 이미지에 포함 — 컨테이너에서 `alembic upgrade head` 실행에 필요.
#   (없으면 배포 시 "No 'script_location' key found in configuration" 오류)
COPY alembic.ini ./

CMD ["uv", "run", "--no-sync", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

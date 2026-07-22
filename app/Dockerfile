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
# 모델 아티팩트가 실제로 들어왔는지 빌드 시점에 확인한다.
#   predictor 가 lru_cache 지연 로딩이라 누락돼도 컨테이너는 뜨고 /health 도 통과한다.
#   그래서 배포 후 첫 예측 요청에서야 500으로 터진다(#127). 여기서 막아 배포 자체를 실패시킨다.
RUN set -e; \
    for f in sarcopenia_model_minimal.joblib sarcopenia_model_with_waist.joblib; do \
        [ -s "app/ml/artifacts/$f" ] || { \
            echo "ERROR: app/ml/artifacts/$f 가 이미지에 없습니다. .dockerignore 예외 규칙을 확인하세요(#127)." >&2; \
            exit 1; \
        }; \
    done

# alembic.ini(마이그레이션 설정)를 이미지에 포함 — 컨테이너에서 `alembic upgrade head` 실행에 필요.
#   (없으면 배포 시 "No 'script_location' key found in configuration" 오류)
COPY alembic.ini ./

# 운영 스크립트(시드·마이그레이션 점검)를 이미지에 포함.
#   미션 템플릿 시드는 마이그레이션이 아니라 별도 스크립트라 배포 후 컨테이너에서 직접 실행해야 하는데,
#   `COPY ./app ./app` 만 있어 이미지에 들어가지 않았다. 그래서 프로덕션에 시드를 넣으려면
#   docker cp 로 밀어넣어야 했고, 재배포하면 사라져 다음 사람이 같은 곳에서 막힌다.
#   .dockerignore 는 scripts/ 를 제외하지 않으므로 COPY 한 줄만 있으면 된다.
COPY ./scripts ./scripts
RUN [ -s "scripts/seed_mission_templates.py" ] || { \
        echo "ERROR: scripts/ 가 이미지에 없습니다. .dockerignore 를 확인하세요." >&2; \
        exit 1; \
    }

CMD ["uv", "run", "--no-sync", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]

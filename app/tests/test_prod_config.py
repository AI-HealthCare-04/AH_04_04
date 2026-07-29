from pathlib import Path

import pytest
from pydantic import ValidationError

from app.core.config import Config, Env


def test_prod_rejects_missing_secrets() -> None:
    with pytest.raises(ValidationError, match=r"SECRET_KEY.*DB_PASSWORD"):
        Config(ENV=Env.PROD, SECRET_KEY="", DB_PASSWORD="")


@pytest.mark.parametrize(
    ("secret_key", "db_password"),
    [
        ("change-me-prod-secret", "strong-db-password"),
        ("strong-secret-key", "pw1234"),
        ("strong-secret-key", "Password1234@"),
    ],
)
def test_prod_rejects_known_placeholder_secrets(secret_key: str, db_password: str) -> None:
    with pytest.raises(ValidationError):
        Config(
            ENV=Env.PROD,
            SECRET_KEY=secret_key,
            DB_PASSWORD=db_password,
        )


def test_prod_accepts_explicit_non_placeholder_secrets() -> None:
    config = Config(
        ENV=Env.PROD,
        SECRET_KEY="qa-only-random-secret-value",
        DB_PASSWORD="qa-only-random-db-password",
    )

    assert config.ENV is Env.PROD


def test_prod_compose_requires_db_environment_variables() -> None:
    compose = (Path(__file__).resolve().parents[2] / "infra" / "docker" / "docker-compose.prod.yml").read_text(
        encoding="utf-8"
    )

    for variable in ("DB_ROOT_PASSWORD", "DB_NAME", "DB_USER", "DB_PASSWORD"):
        assert f"${{{variable}:?" in compose
    assert "Ozcoding1234@" not in compose
    assert "Password1234@" not in compose

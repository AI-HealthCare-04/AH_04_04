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


# 강도 하한(#270)을 만족하는 QA 전용 표본값 — SECRET_KEY 32자+, DB 16자+, 문자 다양성 충분.
QA_SECRET_KEY = "qa-only-9c1d2b8a7e6f5a4d3c2b1a0f9e8dK"  # 37자
QA_DB_PASSWORD = "qa-only-Xk7pQ9zL2m"  # 18자
QA_DB_ROOT_PASSWORD = "qa-only-Rt4vB8nM1w"  # 18자


def test_prod_accepts_explicit_non_placeholder_secrets() -> None:
    config = Config(
        ENV=Env.PROD,
        SECRET_KEY=QA_SECRET_KEY,
        DB_PASSWORD=QA_DB_PASSWORD,
        DB_ROOT_PASSWORD=QA_DB_ROOT_PASSWORD,
    )

    assert config.ENV is Env.PROD


# ── #270: 길이·무작위성 하한 ────────────────────────────────────────────────


def test_prod_accepts_current_operational_profile() -> None:
    """현 운영값 프로필(SECRET_KEY 64자·DB 비밀번호 20자 무작위)이 통과하는지 — 배포 안전 회귀 가드.

    이 테스트가 깨지는 하한 상향은 EC2 .env 로테이션과 함께해야 한다(안 그러면 다음 배포에서 기동 거부).
    """
    config = Config(
        ENV=Env.PROD,
        SECRET_KEY="9c1d2b8a7e6f5a4d3c2b1a0f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d",  # 64자
        DB_PASSWORD="Xk7pQ9zL2mN4vB8rT1wA",  # 20자
        DB_ROOT_PASSWORD="Rt4vB8nM1wQ7kP2zXc9L",  # 20자
    )

    assert config.ENV is Env.PROD


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("SECRET_KEY", "short-but-not-placeholder-31chr"),  # 31자 < 32
        ("DB_PASSWORD", "Xk7pQ9zL2mN4vB8"),  # 15자 < 16
        ("DB_ROOT_PASSWORD", "Rt4vB8nM1wQ7kP2"),  # 15자 < 16
    ],
)
def test_prod_rejects_too_short_secrets(field: str, value: str) -> None:
    values = {
        "SECRET_KEY": QA_SECRET_KEY,
        "DB_PASSWORD": QA_DB_PASSWORD,
        "DB_ROOT_PASSWORD": QA_DB_ROOT_PASSWORD,
        field: value,
    }
    with pytest.raises(ValidationError, match=field):
        Config(ENV=Env.PROD, **values)


def test_prod_rejects_low_variety_secret() -> None:
    # 길이는 충분해도 반복 패턴('ababab…')은 무작위성 부족으로 거부한다.
    with pytest.raises(ValidationError, match="SECRET_KEY"):
        Config(
            ENV=Env.PROD,
            SECRET_KEY="ab" * 20,
            DB_PASSWORD=QA_DB_PASSWORD,
            DB_ROOT_PASSWORD=QA_DB_ROOT_PASSWORD,
        )


def test_error_message_does_not_leak_secret_value() -> None:
    # 기동 실패 로그가 수집돼도 비밀값 단서가 남지 않아야 한다(#270 로그 위생).
    secret = "short-but-not-placeholder-31chr"
    with pytest.raises(ValidationError) as exc:
        Config(ENV=Env.PROD, SECRET_KEY=secret, DB_PASSWORD=QA_DB_PASSWORD, DB_ROOT_PASSWORD=QA_DB_ROOT_PASSWORD)
    assert secret not in str(exc.value)


def test_non_prod_skips_strength_checks() -> None:
    # 로컬/dev 개발 편의: 하한은 PROD 에서만 강제한다(기존 #262 와 동일한 범위).
    config = Config(ENV=Env.DEV, SECRET_KEY="dev", DB_PASSWORD="", DB_ROOT_PASSWORD="")
    assert config.ENV is Env.DEV


def test_prod_compose_requires_db_environment_variables() -> None:
    compose = (Path(__file__).resolve().parents[2] / "infra" / "docker" / "docker-compose.prod.yml").read_text(
        encoding="utf-8"
    )

    for variable in ("DB_ROOT_PASSWORD", "DB_NAME", "DB_USER", "DB_PASSWORD"):
        assert f"${{{variable}:?" in compose
    assert "Ozcoding1234@" not in compose
    assert "Password1234@" not in compose

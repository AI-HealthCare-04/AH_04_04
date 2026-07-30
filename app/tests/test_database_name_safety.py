import pytest

from app.core.db.database_name_safety import validate_ephemeral_database_name


@pytest.mark.parametrize(
    "name",
    [
        "test_ah0404",
        "test_ah0404_mig",
        "qa_242_cleanup",
        "QA_release_candidate_1",
    ],
)
def test_accepts_ephemeral_database_names(name: str) -> None:
    assert validate_ephemeral_database_name(name, application_database="ai_health") == name


@pytest.mark.parametrize(
    "name",
    [
        "ai_health",
        "prod_ai_health",
        "test-unsafe",
        "test_",
        "qa_;DROP_DATABASE",
        "qa_한글",
        "qa_K",
        "teſt_database",
        "qa_İ",
    ],
)
def test_rejects_non_ephemeral_or_unsafe_database_names(name: str) -> None:
    with pytest.raises(ValueError, match="임시 DB 이름"):
        validate_ephemeral_database_name(name, application_database="production")


def test_rejects_application_database_name_case_insensitively() -> None:
    with pytest.raises(ValueError, match="DB_NAME"):
        validate_ephemeral_database_name("test_ah0404", application_database="TEST_AH0404")


def test_rejects_name_over_mysql_limit() -> None:
    with pytest.raises(ValueError, match="64"):
        validate_ephemeral_database_name(f"qa_{'a' * 62}")

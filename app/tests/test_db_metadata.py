from sqlalchemy import Enum as SAEnum
from sqlalchemy import UniqueConstraint

from app.models.base import Base


def test_core_db_metadata_tables() -> None:
    assert set(Base.metadata.tables) == {
        "daily_activity_summaries",
        "game_logs",
        "health_check_sessions",
        "health_profiles",
        "meal_logs",
        "mission_logs",
        "mission_templates",
        "personalized_settings",
        "physical_activity_logs",
        "physical_assessments",
        "point_balances",
        "risk_predictions",
        "sensor_sessions",
        "terms_agreements",
        "user_activity_profiles",
        "users",
    }


def test_deferred_tables_are_not_in_initial_metadata() -> None:
    assert "point_earn_logs" not in Base.metadata.tables
    assert "point_spend_logs" not in Base.metadata.tables
    assert "activity_level_change_logs" not in Base.metadata.tables


def test_user_identity_does_not_store_email_or_age() -> None:
    user_columns = Base.metadata.tables["users"].columns
    health_profile_columns = Base.metadata.tables["health_profiles"].columns

    assert "email" not in user_columns
    assert "age" not in health_profile_columns
    assert "birth_date" in health_profile_columns


def test_terms_agreements_constraints_and_enum_values() -> None:
    terms_agreements = Base.metadata.tables["terms_agreements"]

    unique_constraints = {
        constraint.name: tuple(column.name for column in constraint.columns)
        for constraint in terms_agreements.constraints
        if isinstance(constraint, UniqueConstraint)
    }

    assert unique_constraints["uq_terms_agreements_user_terms_type"] == ("user_id", "terms_type")

    terms_type_type = terms_agreements.columns["terms_type"].type
    assert isinstance(terms_type_type, SAEnum)
    assert terms_type_type.enums == [
        "service",
        "privacy",
        "sensitive_health",
        "marketing",
    ]


def test_timestamp_columns_have_defaults() -> None:
    for table in Base.metadata.tables.values():
        if "created_at" in table.columns:
            assert table.columns["created_at"].server_default is not None

        if "updated_at" in table.columns:
            assert table.columns["updated_at"].server_default is not None
            assert table.columns["updated_at"].onupdate is not None

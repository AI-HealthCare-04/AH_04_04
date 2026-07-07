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

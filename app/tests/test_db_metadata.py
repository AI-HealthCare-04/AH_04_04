from sqlalchemy import Enum as SAEnum
from sqlalchemy import UniqueConstraint

from app.models.base import Base


def test_core_db_metadata_tables() -> None:
    assert set(Base.metadata.tables) == {
        "activity_level_change_logs",
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


def test_v71_enum_contracts() -> None:
    user_provider_type = Base.metadata.tables["users"].columns["provider"].type
    font_size_type = Base.metadata.tables["personalized_settings"].columns["font_size"].type
    sound_size_type = Base.metadata.tables["personalized_settings"].columns["sound_size"].type
    recognition_status_type = Base.metadata.tables["sensor_sessions"].columns["recognition_status"].type

    assert isinstance(user_provider_type, SAEnum)
    assert user_provider_type.enums == ["google", "kakao", "guest"]

    assert isinstance(font_size_type, SAEnum)
    assert font_size_type.enums == ["small", "medium", "large"]

    assert isinstance(sound_size_type, SAEnum)
    assert sound_size_type.enums == ["small", "medium", "large"]

    assert isinstance(recognition_status_type, SAEnum)
    assert recognition_status_type.enums == [
        "success",
        "low_confidence",
        "failed",
        "manual_override",
    ]


def test_level_reason_enum_contract() -> None:
    # 운동 난이도 상태 사유(user_activity_profiles.level_reason)는 명세 확정값만 가진다.
    # #23에서 default/reassessment 제거 + llm_recommendation 추가(마이그레이션 0003)와 계약 일치 검증.
    level_reason_type = Base.metadata.tables["user_activity_profiles"].columns["level_reason"].type
    assert isinstance(level_reason_type, SAEnum)
    assert level_reason_type.enums == [
        "initial_test",
        "rule",
        "llm_recommendation",
        "user_selected",
    ]


def test_activity_level_change_logs_contract() -> None:
    logs = Base.metadata.tables["activity_level_change_logs"]

    assert set(logs.columns.keys()) == {
        "level_change_id",
        "user_id",
        "from_level",
        "to_level",
        "reason_type",
        "reason_text",
        "accepted_by_user",
        "created_at",
    }

    from_level_type = logs.columns["from_level"].type
    to_level_type = logs.columns["to_level"].type
    reason_type_type = logs.columns["reason_type"].type

    assert isinstance(from_level_type, SAEnum)
    assert from_level_type.enums == ["easy", "normal", "hard"]
    assert isinstance(to_level_type, SAEnum)
    assert to_level_type.enums == ["easy", "normal", "hard"]
    assert isinstance(reason_type_type, SAEnum)
    assert reason_type_type.enums == ["rule", "llm_recommendation", "user_request"]


def test_timestamp_columns_have_defaults() -> None:
    for table in Base.metadata.tables.values():
        if "created_at" in table.columns:
            assert table.columns["created_at"].server_default is not None

        if "updated_at" in table.columns:
            assert table.columns["updated_at"].server_default is not None
            assert table.columns["updated_at"].onupdate is not None

from datetime import date
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace

import pytest
from sqlalchemy import String

from app.ml.predictor import (
    AGE_TOPCODE,
    MINIMAL_ARTIFACT_PATH,
    WITH_WAIST_ARTIFACT_PATH,
    AgeNotSupportedError,
    RiskPredictor,
    compute_muscle_score,
    features_from_health_profile,
    has_waist_input,
    load_model_bundle,
    normalize_features,
)
from app.models.enums import ModelVariant, RiskLevel, Sex
from app.models.predictions import RiskPrediction


def test_normalize_features_maps_service_fields() -> None:
    features = normalize_features(
        {
            "age": 72,
            "sex": "female",
            "height_cm": Decimal("154.0"),
            "weight_kg": Decimal("50.0"),
            "walk_days": 7,
            "musc_days": 0,
        }
    )

    assert features["sex"] == 2
    assert features["bmi"] == pytest.approx(21.1)
    assert features["walk_days"] == 7
    assert features["musc_days"] == 0
    assert set(features) == {
        "age",
        "sex",
        "height_cm",
        "weight_kg",
        "bmi",
        "walk_days",
        "musc_days",
    }


def test_normalize_features_includes_waist_when_requested() -> None:
    features = normalize_features(
        {
            "age": 72,
            "sex": "female",
            "height_cm": 154.0,
            "weight_kg": 50.0,
            "waist_cm": 78.0,
            "walk_days": 8,
            "musc_days": 6,
        },
        include_waist=True,
    )

    assert features["waist_cm"] == 78.0
    assert features["walk_days"] == 7
    assert features["musc_days"] == 5
    assert has_waist_input(features) is True


def test_features_from_health_profile() -> None:
    profile = SimpleNamespace(
        birth_date=date(1952, 7, 8),
        sex=Sex.MALE,
        height_cm=Decimal("168.0"),
        weight_kg=Decimal("62.0"),
        bmi=Decimal("22.0"),
        waist_cm=None,
        walk_days=5,
        musc_days=2,
    )

    features = features_from_health_profile(profile)

    assert features["age"] >= 70
    assert features["sex"] == 1
    assert features["height_cm"] == 168.0
    assert features["walk_days"] == 5
    assert features["musc_days"] == 2


async def test_risk_predictor_loads_artifact_and_predicts() -> None:
    result = await RiskPredictor().predict(
        {
            "age": 74,
            "sex": "female",
            "height_cm": 153.0,
            "weight_kg": 48.0,
            "bmi": 20.5,
            "walk_days": 0,
            "musc_days": 0,
        }
    )

    assert 0 <= result.risk_score <= 1
    assert result.risk_level in {RiskLevel.LOW, RiskLevel.MEDIUM, RiskLevel.HIGH}
    assert result.model_variant == ModelVariant.MINIMAL
    assert result.model_version == "sarcopenia_lr_self_report_minimal_days_awgs2025_days_v3"
    assert result.feature_set == "self_report_minimal_days"
    assert result.threshold == pytest.approx(0.20)
    assert result.muscle_score is not None
    assert 0 <= result.muscle_score <= 100
    assert result.score_band in {"good", "maintain", "caution"}
    assert result.score_p_low is not None
    assert result.score_p_high is not None
    assert result.score_cohort_age == "74"
    assert result.score_cohort_version == "knhanes2022_2024_v1"
    assert set(result.input_snapshot) == {
        "age",
        "sex",
        "height_cm",
        "weight_kg",
        "bmi",
        "walk_days",
        "musc_days",
    }


async def test_risk_predictor_uses_waist_model_when_waist_is_present() -> None:
    result = await RiskPredictor().predict(
        {
            "age": 74,
            "sex": "female",
            "height_cm": 153.0,
            "weight_kg": 48.0,
            "bmi": 20.5,
            "waist_cm": 82.0,
            "walk_days": 3,
            "musc_days": 1,
        }
    )

    assert 0 <= result.risk_score <= 1
    assert result.model_variant == ModelVariant.WITH_WAIST
    assert result.model_version == "sarcopenia_lr_self_report_plus_waist_days_awgs2025_days_v3"
    assert result.feature_set == "self_report_plus_waist_days"
    assert result.threshold == pytest.approx(0.20)
    assert result.muscle_score is not None
    assert result.score_band in {"good", "maintain", "caution"}
    assert result.input_snapshot["waist_cm"] == 82.0


async def test_risk_predictor_rejects_under_65() -> None:
    with pytest.raises(AgeNotSupportedError):
        await RiskPredictor().predict(
            {
                "age": 64,
                "sex": "female",
                "height_cm": 153.0,
                "weight_kg": 48.0,
                "bmi": 20.5,
                "walk_days": 3,
                "musc_days": 1,
            }
        )


def test_normalize_features_topcodes_age_80_plus() -> None:
    features = normalize_features(
        {
            "age": 90,
            "sex": "male",
            "height_cm": 168.0,
            "weight_kg": 63.5,
            "bmi": 22.5,
            "walk_days": 5,
            "musc_days": 2,
        }
    )

    assert features["age"] == AGE_TOPCODE


def test_compute_muscle_score_uses_80_plus_cohort() -> None:
    score, band, p_low, p_high, age_key = compute_muscle_score(
        0.2,
        feature_set="minimal",
        sex=1,
        age=80,
    )

    assert score is not None
    assert band in {"good", "maintain", "caution"}
    assert p_low is not None
    assert p_high is not None
    assert age_key == "80+"


@pytest.mark.parametrize(
    ("artifact_path", "expected_feature_set", "expected_model_version", "expected_columns"),
    [
        (
            MINIMAL_ARTIFACT_PATH,
            "self_report_minimal_days",
            "sarcopenia_lr_self_report_minimal_days_awgs2025_days_v3",
            [
                "age",
                "sex",
                "height_cm",
                "weight_kg",
                "bmi",
                "walk_days",
                "musc_days",
            ],
        ),
        (
            WITH_WAIST_ARTIFACT_PATH,
            "self_report_plus_waist_days",
            "sarcopenia_lr_self_report_plus_waist_days_awgs2025_days_v3",
            [
                "age",
                "sex",
                "height_cm",
                "weight_kg",
                "bmi",
                "waist_cm",
                "walk_days",
                "musc_days",
            ],
        ),
    ],
)
def test_awgs2025_artifact_contract(
    artifact_path: Path,
    expected_feature_set: str,
    expected_model_version: str,
    expected_columns: list[str],
) -> None:
    bundle = load_model_bundle(artifact_path)

    assert {
        "model",
        "feature_columns",
        "model_name",
        "feature_set",
        "target_label",
        "selected_threshold",
        "probability_type",
        "model_version",
    } <= bundle.keys()
    assert bundle["feature_columns"] == expected_columns
    assert bundle["model_name"] == "logistic_regression"
    assert bundle["feature_set"] == expected_feature_set
    assert bundle["target_label"] == "sarcopenia_awgs2025"
    assert bundle["selected_threshold"] == pytest.approx(0.20)
    assert bundle["probability_type"] == "raw"
    assert bundle["model_version"] == expected_model_version
    assert list(bundle["model"].classes_) == [0, 1]

    model_version_column_type = RiskPrediction.__table__.c.model_version.type
    assert isinstance(model_version_column_type, String)
    assert model_version_column_type.length is not None
    assert len(bundle["model_version"]) <= model_version_column_type.length

from datetime import date
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.ml.predictor import (
    MINIMAL_ARTIFACT_PATH,
    WITH_WAIST_ARTIFACT_PATH,
    RiskPredictor,
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
            "walking_practice": True,
            "strength_exercise": False,
        }
    )

    assert features["sex"] == 2
    assert features["bmi"] == pytest.approx(21.1)
    assert features["pa_walk_30min_5days"] is True
    assert features["pa_muscle_2days"] is False
    assert set(features) == {
        "age",
        "sex",
        "height_cm",
        "weight_kg",
        "bmi",
        "pa_walk_30min_5days",
        "pa_muscle_2days",
    }


def test_normalize_features_includes_waist_when_requested() -> None:
    features = normalize_features(
        {
            "age": 72,
            "sex": "female",
            "height_cm": 154.0,
            "weight_kg": 50.0,
            "waist_cm": 78.0,
            "walking_practice": True,
            "strength_exercise": False,
        },
        include_waist=True,
    )

    assert features["waist_cm"] == 78.0
    assert has_waist_input(features) is True


def test_features_from_health_profile() -> None:
    profile = SimpleNamespace(
        birth_date=date(1952, 7, 8),
        sex=Sex.MALE,
        height_cm=Decimal("168.0"),
        weight_kg=Decimal("62.0"),
        bmi=Decimal("22.0"),
        waist_cm=None,
        walking_practice=True,
        strength_exercise=True,
    )

    features = features_from_health_profile(profile)

    assert features["age"] >= 70
    assert features["sex"] == 1
    assert features["height_cm"] == 168.0
    assert features["pa_muscle_2days"] is True


async def test_risk_predictor_loads_artifact_and_predicts() -> None:
    result = await RiskPredictor().predict(
        {
            "age": 74,
            "sex": "female",
            "height_cm": 153.0,
            "weight_kg": 48.0,
            "bmi": 20.5,
            "walking_practice": False,
            "strength_exercise": False,
        }
    )

    assert 0 <= result.risk_score <= 1
    assert result.risk_level in {RiskLevel.LOW, RiskLevel.MEDIUM, RiskLevel.HIGH}
    assert result.model_variant == ModelVariant.MINIMAL
    assert result.model_version == "sarcopenia_lr_self_report_minimal_awgs2025_v2"
    assert result.feature_set == "self_report_minimal"
    assert result.threshold == pytest.approx(0.20)
    assert set(result.input_snapshot) == {
        "age",
        "sex",
        "height_cm",
        "weight_kg",
        "bmi",
        "pa_walk_30min_5days",
        "pa_muscle_2days",
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
            "walking_practice": False,
            "strength_exercise": False,
        }
    )

    assert 0 <= result.risk_score <= 1
    assert result.model_variant == ModelVariant.WITH_WAIST
    assert result.model_version == "sarcopenia_lr_self_report_plus_waist_awgs2025_v2"
    assert result.feature_set == "self_report_plus_waist"
    assert result.threshold == pytest.approx(0.20)
    assert result.input_snapshot["waist_cm"] == 82.0


@pytest.mark.parametrize(
    ("artifact_path", "expected_feature_set", "expected_model_version", "expected_columns"),
    [
        (
            MINIMAL_ARTIFACT_PATH,
            "self_report_minimal",
            "sarcopenia_lr_self_report_minimal_awgs2025_v2",
            [
                "age",
                "sex",
                "height_cm",
                "weight_kg",
                "bmi",
                "pa_walk_30min_5days",
                "pa_muscle_2days",
            ],
        ),
        (
            WITH_WAIST_ARTIFACT_PATH,
            "self_report_plus_waist",
            "sarcopenia_lr_self_report_plus_waist_awgs2025_v2",
            [
                "age",
                "sex",
                "height_cm",
                "weight_kg",
                "bmi",
                "waist_cm",
                "pa_walk_30min_5days",
                "pa_muscle_2days",
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

    model_version_max_length = RiskPrediction.__table__.c.model_version.type.length
    assert model_version_max_length is not None
    assert len(bundle["model_version"]) <= model_version_max_length

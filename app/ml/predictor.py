from __future__ import annotations

import asyncio
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from functools import lru_cache
from pathlib import Path
from typing import Any

import joblib  # type: ignore[import-untyped]
import pandas as pd  # type: ignore[import-untyped]

from app.core.utils.clock import today_kst
from app.models.enums import ModelVariant, RiskLevel

ARTIFACT_DIR = Path(__file__).resolve().parent / "artifacts"
MINIMAL_ARTIFACT_PATH = ARTIFACT_DIR / "sarcopenia_model_minimal.joblib"
WITH_WAIST_ARTIFACT_PATH = ARTIFACT_DIR / "sarcopenia_model_with_waist.joblib"
MEDIUM_RISK_THRESHOLD_RATIO = 0.5

MINIMAL_FEATURE_COLUMNS: tuple[str, ...] = (
    "age",
    "sex",
    "height_cm",
    "weight_kg",
    "bmi",
    "pa_walk_30min_5days",
    "pa_muscle_2days",
)

WITH_WAIST_FEATURE_COLUMNS: tuple[str, ...] = (
    "age",
    "sex",
    "height_cm",
    "weight_kg",
    "bmi",
    "waist_cm",
    "pa_walk_30min_5days",
    "pa_muscle_2days",
)


@dataclass(frozen=True)
class RiskPredictionResult:
    risk_score: float
    risk_level: RiskLevel
    model_version: str
    model_variant: ModelVariant
    input_snapshot: dict[str, Any]
    threshold: float
    model_name: str
    feature_set: str


@lru_cache(maxsize=2)
def load_model_bundle(artifact_path: Path) -> dict[str, Any]:
    bundle = joblib.load(artifact_path)
    if not isinstance(bundle, dict) or "model" not in bundle:
        raise ValueError("Invalid sarcopenia model artifact: expected a dict with a 'model' key.")
    return bundle


def calculate_age(birth_date: date, today: date | None = None) -> int:
    today = today or today_kst()
    age = today.year - birth_date.year
    if (today.month, today.day) < (birth_date.month, birth_date.day):
        age -= 1
    return age


def _to_float(value: Any) -> float | None:
    if value is None:
        return None
    if isinstance(value, Decimal):
        return float(value)
    return float(value)


def _normalize_sex(value: Any) -> int | None:
    raw = getattr(value, "value", value)
    if raw is None:
        return None
    if isinstance(raw, str):
        lowered = raw.lower()
        if lowered == "male":
            return 1
        if lowered == "female":
            return 2
    return int(raw)


def has_waist_input(features: Mapping[str, Any]) -> bool:
    return features.get("waist_cm") is not None


def normalize_features(features: Mapping[str, Any], *, include_waist: bool = False) -> dict[str, Any]:
    normalized: dict[str, Any] = {
        "age": _to_float(features.get("age")),
        "sex": _normalize_sex(features.get("sex")),
        "height_cm": _to_float(features.get("height_cm")),
        "weight_kg": _to_float(features.get("weight_kg")),
        "bmi": _to_float(features.get("bmi")),
        "waist_cm": _to_float(features.get("waist_cm")),
        "pa_walk_30min_5days": features.get("pa_walk_30min_5days", features.get("walking_practice")),
        "pa_muscle_2days": features.get("pa_muscle_2days", features.get("strength_exercise")),
    }

    if normalized["bmi"] is None and normalized["height_cm"] and normalized["weight_kg"]:
        height_m = normalized["height_cm"] / 100
        normalized["bmi"] = round(normalized["weight_kg"] / (height_m * height_m), 1)

    feature_columns = WITH_WAIST_FEATURE_COLUMNS if include_waist else MINIMAL_FEATURE_COLUMNS
    return {column: normalized.get(column) for column in feature_columns}


def features_from_health_profile(profile: Any) -> dict[str, Any]:
    return normalize_features(
        {
            "age": calculate_age(profile.birth_date),
            "sex": profile.sex,
            "height_cm": profile.height_cm,
            "weight_kg": profile.weight_kg,
            "bmi": profile.bmi,
            "waist_cm": profile.waist_cm,
            "walking_practice": profile.walking_practice,
            "strength_exercise": profile.strength_exercise,
        },
        include_waist=profile.waist_cm is not None,
    )


def _risk_level(score: float, threshold: float) -> RiskLevel:
    if score >= threshold:
        return RiskLevel.HIGH
    # MVP uses a conservative middle band below the model-selected high-risk threshold.
    if score >= threshold * MEDIUM_RISK_THRESHOLD_RATIO:
        return RiskLevel.MEDIUM
    return RiskLevel.LOW



class RiskPredictor:
    def __init__(
        self,
        minimal_artifact_path: Path = MINIMAL_ARTIFACT_PATH,
        with_waist_artifact_path: Path = WITH_WAIST_ARTIFACT_PATH,
    ):
        self.minimal_artifact_path = minimal_artifact_path
        self.with_waist_artifact_path = with_waist_artifact_path

    async def predict(self, features: Mapping[str, Any]) -> RiskPredictionResult:
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(None, self.predict_sync, features)

    def predict_sync(self, features: Mapping[str, Any]) -> RiskPredictionResult:
        include_waist = has_waist_input(features)
        artifact_path = self.with_waist_artifact_path if include_waist else self.minimal_artifact_path
        bundle = load_model_bundle(artifact_path)
        model = bundle["model"]
        feature_columns = tuple(
            bundle.get("feature_columns")
            or (WITH_WAIST_FEATURE_COLUMNS if include_waist else MINIMAL_FEATURE_COLUMNS)
        )
        snapshot = normalize_features(features, include_waist=include_waist)
        frame = pd.DataFrame([{column: snapshot.get(column) for column in feature_columns}], columns=feature_columns)
        probabilities = model.predict_proba(frame)[0]
        positive_index = list(model.classes_).index(1)
        score = float(probabilities[positive_index])
        threshold = float(bundle.get("selected_threshold") or 0.5)
        level = _risk_level(score, threshold)
        return RiskPredictionResult(
            risk_score=score,
            risk_level=level,
            model_version=str(bundle.get("model_version") or f"sarcopenia_lr_{bundle.get('feature_set', 'unknown')}_v1"),
            model_variant=ModelVariant.WITH_WAIST if include_waist else ModelVariant.MINIMAL,
            input_snapshot=snapshot,
            threshold=threshold,
            model_name=str(bundle.get("model_name", "unknown")),
            feature_set=str(bundle.get("feature_set", "unknown")),
        )

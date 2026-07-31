from __future__ import annotations

import asyncio
import bisect
import json
import logging
from collections.abc import Mapping, Sequence
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

logger = logging.getLogger(__name__)

ARTIFACT_DIR = Path(__file__).resolve().parent / "artifacts"
MINIMAL_ARTIFACT_PATH = ARTIFACT_DIR / "sarcopenia_model_minimal.joblib"
WITH_WAIST_ARTIFACT_PATH = ARTIFACT_DIR / "sarcopenia_model_with_waist.joblib"
COHORT_TABLE_PATH = ARTIFACT_DIR / "cohort_unified_65plus.json"
MEDIUM_RISK_THRESHOLD_RATIO = 0.5

# v1 = 65세 이상 전용. 국건영 원자료 나이 top-coding(80+)과 학습 구간에 맞춤.
AGE_MIN = 65
AGE_TOPCODE = 80

# 점수 구간 컷오프 기본값(모델 config가 없을 때 폴백). 실제 값은 model_config.yaml의 score 섹션에서 읽는다.
DEFAULT_SCORE_GOOD_MIN = 75      # score >= 75  -> 좋음
DEFAULT_SCORE_CAUTION_MAX = 29   # score <= 29  -> 주의 (그 사이 = 유지)
DEFAULT_DISPLAY_FLOOR = 5        # 화면 표시 하한(계산·저장은 0~100 유지, 프런트가 적용)

MINIMAL_FEATURE_COLUMNS: tuple[str, ...] = (
    "age",
    "sex",
    "height_cm",
    "weight_kg",
    "bmi",
    "walk_days",
    "musc_days",
)

WITH_WAIST_FEATURE_COLUMNS: tuple[str, ...] = (
    "age",
    "sex",
    "height_cm",
    "weight_kg",
    "bmi",
    "waist_cm",
    "walk_days",
    "musc_days",
)


class AgeNotSupportedError(ValueError):
    """v1은 65세 이상만 지원. 그 미만 나이는 예측을 내지 않고 호출부가 '준비 중' 안내로 처리한다."""

    def __init__(self, age: Any):
        self.age = age
        super().__init__(f"Sarcopenia score is provided for age >= {AGE_MIN} only (got age={age}).")


@dataclass(frozen=True)
class RiskPredictionResult:
    risk_score: float           # 근감소증 추정 확률 (모델 원출력, 0~1) — 온보딩/내부용
    risk_level: RiskLevel       # 확률 기반 3구간(유지, 온보딩용)
    model_version: str
    model_variant: ModelVariant
    input_snapshot: dict[str, Any]
    threshold: float
    model_name: str
    feature_set: str
    # --- 기록탭 긍정 점수(또래 대비, 높을수록 좋음) ---
    muscle_score: int | None = None      # 0~100 정수 (계산·저장값). None이면 코호트표 조회 실패
    score_band: str | None = None        # "good" / "maintain" / "caution"
    score_p_low: float | None = None     # 조회에 쓴 코호트 P5
    score_p_high: float | None = None    # 조회에 쓴 코호트 P95
    score_cohort_age: str | None = None  # "72" 또는 "80+" 등 실제 조회 키
    score_cohort_version: str | None = None


@lru_cache(maxsize=2)
def load_model_bundle(artifact_path: Path) -> dict[str, Any]:
    bundle = joblib.load(artifact_path)
    if not isinstance(bundle, dict) or "model" not in bundle:
        raise ValueError("Invalid sarcopenia model artifact: expected a dict with a 'model' key.")
    return bundle


@lru_cache(maxsize=1)
def load_score_config() -> dict[str, float]:
    """model_config.yaml의 score 섹션에서 컷오프를 읽는다(없으면 기본값)."""
    good_min: float = float(DEFAULT_SCORE_GOOD_MIN)
    caution_max: float = float(DEFAULT_SCORE_CAUTION_MAX)
    floor: float = float(DEFAULT_DISPLAY_FLOOR)
    cfg_path = ARTIFACT_DIR / "model_config.yaml"
    try:
        import yaml  # type: ignore[import-untyped]

        cfg = yaml.safe_load(cfg_path.read_text(encoding="utf-8")) or {}
        score = cfg.get("score", {}) or {}
        good_min = float(score.get("good_min", good_min))
        caution_max = float(score.get("caution_max", caution_max))
        floor = float(score.get("display_floor", floor))
    except Exception:
        logger.warning("Failed to load sarcopenia score config; using defaults.", exc_info=True)
    return {"good_min": good_min, "caution_max": caution_max, "display_floor": floor}


@lru_cache(maxsize=1)
def load_cohort_version() -> str | None:
    if not COHORT_TABLE_PATH.exists():
        return None
    data = json.loads(COHORT_TABLE_PATH.read_text(encoding="utf-8"))
    meta = data.get("meta", {}) or {}
    version = meta.get("cohort_version")
    return str(version) if version is not None else None


@lru_cache(maxsize=1)
def load_cohort_table() -> dict[tuple[str, int, str], tuple[float, float]]:
    """(feature_set, sex, age_key) -> (p_low, p_high). age_key는 '65'~'79' 또는 '80+'."""
    lut: dict[tuple[str, int, str], tuple[float, float]] = {}
    if not COHORT_TABLE_PATH.exists():
        return lut
    data = json.loads(COHORT_TABLE_PATH.read_text(encoding="utf-8"))
    for c in data.get("cohorts", []):
        key = (str(c["feature_set"]), int(c["sex"]), str(c["age"]))
        lut[key] = (float(c["p_low"]), float(c["p_high"]))
    return lut


@lru_cache(maxsize=1)
def load_cohort_model_version() -> str | None:
    """코호트 산출물의 모델 버전(meta.model_version). 없으면 None."""
    if not COHORT_TABLE_PATH.exists():
        return None
    data = json.loads(COHORT_TABLE_PATH.read_text(encoding="utf-8"))
    meta = data.get("meta", {}) or {}
    version = meta.get("model_version")
    return str(version) if version is not None else None


@dataclass(frozen=True)
class CohortDistribution:
    """또래 분포 차트(#193)용 코호트 데이터. quantiles(백분위)와 density(곡선), 표본수·창·P5/P95."""

    quantiles: tuple[float, ...]
    density: tuple[tuple[float, float], ...]
    n: int
    window: str | None
    p_low: float
    p_high: float


@lru_cache(maxsize=1)
def load_cohort_distribution() -> dict[tuple[str, int, str], CohortDistribution]:
    """(feature_set, sex, age_key) -> CohortDistribution. load_cohort_table 과 달리 quantiles·density 를 보존한다.

    density 는 코호트 구성원의 예측확률 분포에서 산출한 KDE 곡선을 공용 산출물에서 그대로 읽는다.
    """
    out: dict[tuple[str, int, str], CohortDistribution] = {}
    if not COHORT_TABLE_PATH.exists():
        return out
    data = json.loads(COHORT_TABLE_PATH.read_text(encoding="utf-8"))
    for c in data.get("cohorts", []):
        key = (str(c["feature_set"]), int(c["sex"]), str(c["age"]))
        quantiles = tuple(float(x) for x in c["quantiles"])
        density = tuple((float(x), float(y)) for x, y in c["density"])
        out[key] = CohortDistribution(
            quantiles=quantiles,
            density=density,
            n=int(c.get("n", 0)),
            window=(str(c["window"]) if c.get("window") is not None else None),
            p_low=float(c["p_low"]),
            p_high=float(c["p_high"]),
        )
    return out


def percentile_low(probability: float, quantiles: Sequence[float]) -> float:
    """예측확률 p 의 코호트 내 백분위(0.0~100.0, 분위수 101개 선형보간, 스펙 §4.1).

    quantiles 는 오름차순 예측확률 q0..q100. 반환값이 클수록 위험이 높은 쪽(나보다 위험이 낮은 사람 비율).
    """
    q = quantiles
    if len(q) < 2:
        return 0.0
    if probability <= q[0]:
        return 0.0
    if probability >= q[-1]:
        return 100.0
    # q[i] <= p < q[i+1] 인 마지막 i 를 찾아 선형보간.
    i = bisect.bisect_right(q, probability) - 1
    q_i, q_i1 = q[i], q[i + 1]
    if q_i1 <= q_i:
        return float(i)
    return i + (probability - q_i) / (q_i1 - q_i)


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


def _clamp_float(value: Any, *, lower: float, upper: float) -> float | None:
    numeric = _to_float(value)
    if numeric is None:
        return None
    return min(max(numeric, lower), upper)


def has_waist_input(features: Mapping[str, Any]) -> bool:
    return features.get("waist_cm") is not None


def normalize_features(features: Mapping[str, Any], *, include_waist: bool = False) -> dict[str, Any]:
    age = _to_float(features.get("age"))
    # 학습 원자료가 80세 top-coding이므로 모델 입력 나이는 80으로 상한(80+ 외삽 방지).
    if age is not None and age > AGE_TOPCODE:
        age = float(AGE_TOPCODE)

    normalized: dict[str, Any] = {
        "age": age,
        "sex": _normalize_sex(features.get("sex")),
        "height_cm": _to_float(features.get("height_cm")),
        "weight_kg": _to_float(features.get("weight_kg")),
        "bmi": _to_float(features.get("bmi")),
        "waist_cm": _to_float(features.get("waist_cm")),
        "walk_days": _clamp_float(features.get("walk_days"), lower=0, upper=7),
        "musc_days": _clamp_float(features.get("musc_days"), lower=0, upper=5),
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
            "walk_days": profile.walk_days,
            "musc_days": profile.musc_days,
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


def _cohort_age_key(age: float) -> str:
    """코호트표 조회 키. 80세 이상은 '80+', 그 외는 정수 나이 문자열."""
    a = int(round(age))
    if a >= AGE_TOPCODE:
        return f"{AGE_TOPCODE}+"
    return str(a)


def compute_muscle_score(
    probability: float,
    *,
    feature_set: str,
    sex: int | None,
    age: float | None,
) -> tuple[int | None, str | None, float | None, float | None, str | None]:
    """또래 대비 긍정 점수(선형 P5/P95). (score, band, p_low, p_high, age_key)."""
    if sex is None or age is None:
        return None, None, None, None, None
    lut = load_cohort_table()
    age_key = _cohort_age_key(age)
    entry = lut.get((feature_set, int(sex), age_key))
    if entry is None:
        return None, None, None, None, age_key
    p_low, p_high = entry
    if p_high <= p_low:
        return None, None, p_low, p_high, age_key
    raw = 100.0 * (p_high - probability) / (p_high - p_low)
    score = int(round(min(100.0, max(0.0, raw))))

    cfg = load_score_config()
    if score >= cfg["good_min"]:
        band = "good"
    elif score <= cfg["caution_max"]:
        band = "caution"
    else:
        band = "maintain"
    return score, band, p_low, p_high, age_key


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
        # v1 연령 게이트: 65세 미만은 예측하지 않는다(65+ 모델 외삽 방지).
        raw_age = _to_float(features.get("age"))
        if raw_age is None or raw_age < AGE_MIN:
            raise AgeNotSupportedError(raw_age)

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

        feature_set = str(bundle.get("feature_set", "unknown"))
        # 코호트표 조회 키는 minimal / with_waist (번들 feature_set 문자열과 다름)
        cohort_feature_set = "with_waist" if include_waist else "minimal"
        muscle_score, band, p_low, p_high, age_key = compute_muscle_score(
            score,
            feature_set=cohort_feature_set,
            sex=snapshot.get("sex"),
            age=snapshot.get("age"),
        )

        return RiskPredictionResult(
            risk_score=score,
            risk_level=level,
            model_version=str(bundle.get("model_version") or "sarcopenia_lr_unknown"),
            model_variant=ModelVariant.WITH_WAIST if include_waist else ModelVariant.MINIMAL,
            input_snapshot=snapshot,
            threshold=threshold,
            model_name=str(bundle.get("model_name", "unknown")),
            feature_set=feature_set,
            muscle_score=muscle_score,
            score_band=band,
            score_p_low=p_low,
            score_p_high=p_high,
            score_cohort_age=age_key,
            score_cohort_version=load_cohort_version() if muscle_score is not None else None,
        )

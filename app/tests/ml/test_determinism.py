"""
심사 3-3 — 동일 입력 결과 편차 최소화(결정성) 검증.

3층 구조:
  ① 추론 결정성   : 같은 입력으로 predict_sync 100회 -> risk_score·muscle_score·score_band 전부 '=='(비트 단위)
  ② 아티팩트 무결성: 배포 joblib의 SHA-256이 고정값과 일치 -> "모델이 안 바뀌었다"까지 보장(①의 전제)
  ③ 시스템 결정성 : 시계를 고정하고 프로필→추론 전체 경로를 N회 -> 코호트표 조회·나이계산 포함 동일

주의: 나이는 today에 의존하므로(생일 경과 시 변하는 건 의도된 동작), 결정성 테스트는
      입력을 나이로 고정하거나(①②) 시계를 고정한다(③). 근사비교(approx) 금지 — 같은 입력·같은 모델이면
      부동소수까지 비트 단위로 동일해야 정상이다.
"""
from __future__ import annotations

import hashlib
from datetime import date
from pathlib import Path
from types import SimpleNamespace

import pytest

import app.ml.predictor as predictor_mod
from app.ml.predictor import (
    ARTIFACT_DIR,
    AgeNotSupportedError,
    RiskPredictor,
    features_from_health_profile,
)

# ── ② 고정 해시 (배포 아티팩트) ─────────────────────────────
EXPECTED_SHA256 = {
    "sarcopenia_model_minimal.joblib":
        "987287e8be9daa87487595865d2113b7f298cdd3248ed107adcd7b580ac4ffe5",
    "sarcopenia_model_with_waist.joblib":
        "0b5052862a66d1a429e3632a4d2eac00babbf3546be6c38748ab52670c5c2c10",
}

# 나이로 고정한 입력(시계 비의존). 허리 없으면 minimal, 있으면 with_waist 모델로 라우팅.
INPUT_MINIMAL = {"age": 72, "sex": 1, "height_cm": 168, "weight_kg": 68, "walk_days": 3, "musc_days": 1}
INPUT_WITH_WAIST = {**INPUT_MINIMAL, "waist_cm": 88}


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


# ── ② 아티팩트 무결성 ──────────────────────────────────────
@pytest.mark.parametrize("name,expected", EXPECTED_SHA256.items())
def test_artifact_integrity(name, expected):
    assert _sha256(ARTIFACT_DIR / name) == expected, f"{name} 아티팩트가 배포본과 다릅니다."


# ── ① 추론 결정성 (100회, ==) ─────────────────────────────
@pytest.mark.parametrize("features", [INPUT_MINIMAL, INPUT_WITH_WAIST])
def test_inference_is_deterministic(features):
    pred = RiskPredictor()
    r0 = pred.predict_sync(features)
    for _ in range(100):
        r = pred.predict_sync(features)
        assert r.risk_score == r0.risk_score          # 비트 단위 동일
        assert r.muscle_score == r0.muscle_score
        assert r.score_band == r0.score_band
        assert r.model_variant == r0.model_variant


# ── ③ 시스템 결정성 (시계 고정, 전체 경로 N회) ─────────────
def test_full_pipeline_deterministic_with_frozen_clock(monkeypatch):
    monkeypatch.setattr(predictor_mod, "today_kst", lambda: date(2026, 8, 1))
    profile = SimpleNamespace(
        birth_date=date(1954, 3, 10),  # 고정 시계 기준 만 72세
        sex=1, height_cm=168, weight_kg=68, bmi=None, waist_cm=88,
        walk_days=3, musc_days=1,
    )
    pred = RiskPredictor()
    r0 = pred.predict_sync(features_from_health_profile(profile))
    for _ in range(50):
        r = pred.predict_sync(features_from_health_profile(profile))
        assert r.risk_score == r0.risk_score
        assert r.muscle_score == r0.muscle_score
        assert r.score_band == r0.score_band


# ── (보너스) 연령 게이트도 결정적 ─────────────────────────
def test_age_gate_below_65_raises():
    pred = RiskPredictor()
    for age in (64, 60, 50):
        with pytest.raises(AgeNotSupportedError):
            pred.predict_sync({**INPUT_MINIMAL, "age": age})

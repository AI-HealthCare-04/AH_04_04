"""근육 점수 SHAP 기여도(#406) — 닫힌형(계수×표준화값) 계산 검증.

핵심: LR coef_ 순서는 feature_columns 가 아니라 전처리 출력 순서라, get_feature_names_out 정렬이
맞아야 부호가 안 뒤집힌다. 이슈 #406 예시(허리 ≈ -1.29 / 근력 ≈ -0.17 / 걷기 ≈ -0.13) 재현으로 고정한다.
"""

import pytest

from app.ml.predictor import compute_score_contributions, normalize_features


def _snapshot(**raw: object) -> dict:
    """실제 저장 경로와 동일하게 normalize_features 로 만든 input_snapshot."""
    include_waist = raw.get("waist_cm") is not None
    return normalize_features(raw, include_waist=include_waist)


def test_only_three_modifiable_features_exposed() -> None:
    snap = _snapshot(age=72, sex="male", height_cm=168, weight_kg=78, waist_cm=98, walk_days=1, musc_days=0)
    feats = {c.feature for c in compute_score_contributions(snap)}
    assert feats == {"musc_days", "walk_days", "waist_cm"}
    # 비가역(나이·성별·키)·오해 소지(체중·BMI) 특징은 절대 노출하지 않는다.
    assert feats.isdisjoint({"age", "sex", "height_cm", "weight_kg", "bmi"})


def test_reproduces_issue_example_user_a() -> None:
    # 남 72세·허리 98·저활동(걷기 1·근력 0). 이슈 #406 예시값을 그대로 재현해야 한다.
    snap = _snapshot(age=72, sex="male", height_cm=168, weight_kg=78, waist_cm=98, walk_days=1, musc_days=0)
    by = {c.feature: c.effect_on_score_log_odds for c in compute_score_contributions(snap)}
    assert by["waist_cm"] == pytest.approx(-1.29, abs=0.05)
    assert by["musc_days"] == pytest.approx(-0.17, abs=0.05)
    assert by["walk_days"] == pytest.approx(-0.13, abs=0.05)
    # 개선 여지(점수를 가장 많이 끌어내리는 항목)는 허리.
    assert min(by, key=by.__getitem__) == "waist_cm"


def test_health_direction_active_user_positive_contributions() -> None:
    # 걷기·근력을 많이 하면 그 기여가 점수를 올리는 방향(양수)이어야 한다 — 부호·정렬 회귀 방지.
    snap = _snapshot(age=72, sex="male", height_cm=168, weight_kg=65, waist_cm=82, walk_days=7, musc_days=5)
    by = {c.feature: c.effect_on_score_log_odds for c in compute_score_contributions(snap)}
    assert by["musc_days"] > 0
    assert by["walk_days"] > 0
    # 허리가 가늘면(표준화값 음수) 점수를 올리는 방향(양수)이어야 한다.
    assert by["waist_cm"] > 0


def test_minimal_model_omits_waist() -> None:
    # 허리 미입력이면 minimal 모델이라 waist_cm 은 빠지고 근력·걷기만 나온다.
    snap = _snapshot(age=72, sex="male", height_cm=168, weight_kg=65, walk_days=3, musc_days=2)
    feats = {c.feature for c in compute_score_contributions(snap)}
    assert feats == {"musc_days", "walk_days"}


def test_degenerate_snapshot_never_raises() -> None:
    # 기여도는 부가 정보 — 이상한 스냅샷에도 예외 없이 리스트를 돌려줘 본 응답을 막지 않는다.
    # 값이 없으면(빈 dict·무관 키) 임퓨트 대표값으로 가짜 막대를 만들지 않고 빈 목록을 낸다(#406 리뷰).
    for snap in ({}, {"foo": "bar"}):
        assert compute_score_contributions(snap) == []


def test_missing_feature_value_is_skipped_not_imputed() -> None:
    # 개별 특징이 None 이면 임퓨터 대표값으로 채운 가짜 기여도를 만들지 않고 그 특징만 건너뛴다(#406 리뷰).
    #   walk_days 만 주면 musc_days(None)는 빠지고 walk_days 만 남는다. NaN 도 없어야 한다.
    snap = _snapshot(age=72, sex="male", height_cm=168, weight_kg=65, walk_days=3, musc_days=None)
    out = compute_score_contributions(snap)
    feats = {c.feature for c in out}
    assert feats == {"walk_days"}
    assert all(c.effect_on_score_log_odds == c.effect_on_score_log_odds for c in out)  # NaN 아님

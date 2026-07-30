# =====================================================================================
# 근육 건강 점수 파생(#기록탭 §3.1) 단위 테스트 — DB 불필요(순수 함수·주입 코호트표).
#   공식·구간(75/30)·연령 게이트·80+ 풀·feature_set 매칭·표 부재 시 None 을 고정한다.
# =====================================================================================
import pytest

from app.models.enums import ModelVariant
from app.services.muscle_score import (
    CohortQuantiles,
    CohortTable,
    band_of,
    compute_muscle_score,
    feature_set_of,
    linear_score,
)


def _table() -> CohortTable:
    # p_low=0.1, p_high=0.5 인 단순 코호트(65세·80+). 점수 = 100×(0.5−p)/0.4.
    q = CohortQuantiles(p_low=0.1, p_high=0.5)
    return CohortTable(
        cohort_version="test-v1",
        cohorts={
            "minimal": {"male": {"65": q, "80+": q}, "female": {"70": q}},
            "with_waist": {"male": {"65": q}},
        },
    )


# ── 선형 공식 ───────────────────────────────────────────────
def test_linear_score_endpoints_and_midpoint() -> None:
    assert linear_score(0.1, 0.1, 0.5) == 100  # 또래 최저 위험 → 100
    assert linear_score(0.5, 0.1, 0.5) == 0  # 또래 최고 위험 → 0
    assert linear_score(0.3, 0.1, 0.5) == 50  # 중간


def test_linear_score_clamps_out_of_range() -> None:
    assert linear_score(0.0, 0.1, 0.5) == 100  # p_low 아래도 100 클램프
    assert linear_score(0.9, 0.1, 0.5) == 0  # p_high 위도 0 클램프


def test_linear_score_rejects_degenerate_range() -> None:
    with pytest.raises(ValueError):
        linear_score(0.2, 0.5, 0.5)


# ── 구간(75/30 확정) ────────────────────────────────────────
@pytest.mark.parametrize(
    ("score", "band"),
    [(100, "good"), (75, "good"), (74, "maintain"), (30, "maintain"), (29, "caution"), (0, "caution")],
)
def test_band_of_cutoffs(score: int, band: str) -> None:
    assert band_of(score) == band


def test_feature_set_mapping() -> None:
    assert feature_set_of(ModelVariant.MINIMAL) == "minimal"
    assert feature_set_of(ModelVariant.WITH_WAIST) == "with_waist"
    assert feature_set_of(ModelVariant.RULE_BASED_SCAFFOLD) is None  # scaffold 는 점수 대상 아님


# ── compute_muscle_score 통합(주입 표) ──────────────────────
def test_compute_returns_score_and_band_and_version() -> None:
    result = compute_muscle_score(
        probability=0.3, sex="male", age=65, model_variant=ModelVariant.MINIMAL, table=_table()
    )
    assert result is not None
    assert result.score == 50 and result.band == "maintain" and result.cohort_version == "test-v1"


def test_compute_gates_under_65() -> None:
    assert (
        compute_muscle_score(probability=0.3, sex="male", age=64, model_variant=ModelVariant.MINIMAL, table=_table())
        is None
    )


def test_compute_uses_80plus_pool() -> None:
    # 82세는 "80+" 풀 사용(단일나이 82 행이 없어도 조회 성공).
    result = compute_muscle_score(
        probability=0.1, sex="male", age=82, model_variant=ModelVariant.MINIMAL, table=_table()
    )
    assert result is not None and result.score == 100


def test_compute_none_when_table_empty() -> None:
    empty = CohortTable(cohort_version="x", cohorts={})
    assert (
        compute_muscle_score(probability=0.3, sex="male", age=70, model_variant=ModelVariant.MINIMAL, table=empty)
        is None
    )


def test_compute_none_for_scaffold_variant() -> None:
    assert (
        compute_muscle_score(
            probability=0.3, sex="male", age=65, model_variant=ModelVariant.RULE_BASED_SCAFFOLD, table=_table()
        )
        is None
    )


def test_compute_none_when_cohort_missing() -> None:
    # 표에 없는 성별/나이 → None(점수 미제공, 앱은 "준비 중").
    assert (
        compute_muscle_score(probability=0.3, sex="female", age=65, model_variant=ModelVariant.MINIMAL, table=_table())
        is None
    )

# =====================================================================================
# 근육 건강 점수(0~100) 파생 — 기록 탭 §3.1 / 결정 근거 ADR-2·ADR-3·ADR-6(2026-07-30, 지영님).
#
# 점수는 저장하지 않고 **읽는 시점에** 예측확률 p(=internal_risk_score)와 코호트 분위수표에서 파생한다.
#   score = round(100 × (p_high − p) / (p_high − p_low)),  [0,100] 클램프
#   p_low = q5, p_high = q95  (코호트 = feature_set × 성별 × 단일나이. 창 정의는 표 생성측(모델팀) 사항)
#
# 코호트 분위수표(`cohort_unified_65plus.json`)는 모델팀 산출물이다. **파일이 없으면 점수는 None**
#   → API 가 score=null 을 내려 앱이 "점수 준비 중"으로 표시한다. 표가 들어오면 코드 변경 없이 켜진다.
#
# 연령 게이트: age < 65 는 None(앱이 §3.3 카드). age ≥ 80 은 표의 "80+" 풀 1행을 쓴다.
# 구간(75/30 확정): score ≥ 75 good / 30–74 maintain / ≤ 29 caution.
# 표시 하한 5점은 앱 표시 규칙이며(계산·저장은 0~100 유지) 여기서는 적용하지 않는다.
# =====================================================================================
from __future__ import annotations

import json
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from app.models.enums import ModelVariant

COHORT_TABLE_PATH = Path(__file__).resolve().parent.parent / "ml" / "artifacts" / "cohort_unified_65plus.json"

MIN_SCORE_AGE = 65  # <65 는 점수 미제공(연령 게이트, ADR-5)
GOOD_CUTOFF = 75    # ≥75 좋음
CAUTION_CUTOFF = 29  # ≤29 주의, 그 사이는 유지


@dataclass(frozen=True)
class CohortQuantiles:
    p_low: float   # q5
    p_high: float  # q95


@dataclass(frozen=True)
class CohortTable:
    cohort_version: str
    # feature_set("minimal"|"with_waist") -> sex("male"|"female") -> age_key("65".."79"|"80+") -> quantiles
    cohorts: dict[str, dict[str, dict[str, CohortQuantiles]]]

    def lookup(self, feature_set: str, sex: str, age: int) -> CohortQuantiles | None:
        by_sex = self.cohorts.get(feature_set)
        if by_sex is None:
            return None
        by_age = by_sex.get(sex)
        if by_age is None:
            return None
        key = "80+" if age >= 80 else str(age)  # 국건영 나이 탑코딩 → 80+ 별도 풀
        return by_age.get(key)


@lru_cache(maxsize=1)
def load_cohort_table() -> CohortTable | None:
    """모델팀 산출물을 로드. 파일 부재 시 None(점수 미제공). 결과는 캐시된다."""
    if not COHORT_TABLE_PATH.exists():
        return None
    raw = json.loads(COHORT_TABLE_PATH.read_text(encoding="utf-8"))
    cohorts: dict[str, dict[str, dict[str, CohortQuantiles]]] = {}
    for feature_set, by_sex in raw.get("cohorts", {}).items():
        cohorts[feature_set] = {}
        for sex, by_age in by_sex.items():
            cohorts[feature_set][sex] = {
                age_key: CohortQuantiles(p_low=float(q["q5"]), p_high=float(q["q95"]))
                for age_key, q in by_age.items()
            }
    return CohortTable(cohort_version=str(raw.get("cohort_version", "unknown")), cohorts=cohorts)


def feature_set_of(model_variant: ModelVariant) -> str | None:
    """예측 변형 → 코호트 feature_set. 허리 유무 2모델을 그대로 매칭(ADR 허리결측 정정). scaffold 는 점수 대상 아님."""
    if model_variant == ModelVariant.WITH_WAIST:
        return "with_waist"
    if model_variant == ModelVariant.MINIMAL:
        return "minimal"
    return None


def linear_score(probability: float, p_low: float, p_high: float) -> int:
    """선형 P5/P95 점수(순수 함수, 표 무관 — 단위 테스트 대상). [0,100] 클램프."""
    if p_high <= p_low:
        raise ValueError("p_high must be greater than p_low")
    raw = round(100 * (p_high - probability) / (p_high - p_low))
    return max(0, min(100, int(raw)))


def band_of(score: int) -> str:
    """점수 → 구간(75/30 확정)."""
    if score >= GOOD_CUTOFF:
        return "good"
    if score <= CAUTION_CUTOFF:
        return "caution"
    return "maintain"


@dataclass(frozen=True)
class MuscleScore:
    score: int
    band: str  # good | maintain | caution
    cohort_version: str


def compute_muscle_score(
    *,
    probability: float,
    sex: str,
    age: int | None,
    model_variant: ModelVariant,
    table: CohortTable | None = None,
) -> MuscleScore | None:
    """예측확률 + 코호트 표에서 근육 건강 점수를 파생. 어느 전제라도 못 갖추면 None(=score 미제공)."""
    table = table if table is not None else load_cohort_table()
    if table is None:
        return None
    if age is None or age < MIN_SCORE_AGE:  # 연령 게이트
        return None
    feature_set = feature_set_of(model_variant)
    if feature_set is None:
        return None
    quantiles = table.lookup(feature_set, sex, age)
    if quantiles is None or quantiles.p_high <= quantiles.p_low:
        return None
    score = linear_score(probability, quantiles.p_low, quantiles.p_high)
    return MuscleScore(score=score, band=band_of(score), cohort_version=table.cohort_version)

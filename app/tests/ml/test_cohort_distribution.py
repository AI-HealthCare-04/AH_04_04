"""또래 분포 차트(#193) 순수 로직: 백분위 선형보간·코호트 로더."""

import pytest

from app.ml.predictor import load_cohort_distribution, percentile_low


# ---------------- 백분위(§4.1) ----------------
def test_percentile_low_boundaries_and_interpolation() -> None:
    q = [i / 100.0 for i in range(101)]  # 0.00 .. 1.00 균일
    assert percentile_low(-1.0, q) == 0.0  # q0 이하 → 0
    assert percentile_low(2.0, q) == 100.0  # q100 이상 → 100
    assert percentile_low(0.50, q) == pytest.approx(50.0)  # 정확 분위수
    assert percentile_low(0.505, q) == pytest.approx(50.5, abs=1e-6)  # 사이 선형보간


def test_percentile_low_monotonic() -> None:
    q = sorted(0.5 * (i / 100.0) ** 1.5 for i in range(101))  # 우편향 오름차순
    prev = -1.0
    for p in (0.0, 0.01, 0.05, 0.1, 0.2, 0.4, 0.6):
        v = percentile_low(p, q)
        assert 0.0 <= v <= 100.0
        assert v >= prev  # 확률이 커질수록 백분위(위험 높은 쪽)도 커진다
        prev = v


def test_percentile_low_handles_short_input() -> None:
    assert percentile_low(0.1, [0.2]) == 0.0  # 분위수 부족 → 0


# ---------------- lower_count 클램프(§4.1: [1,99]) ----------------
def test_lower_count_clamp_expression() -> None:
    # 서비스가 적용하는 클램프. p 가 극단이면 0/100 이 나오지만 1/99 로 막는다(0번째/101번째 방지).
    def clamp(pct: float) -> int:
        return min(99, max(1, round(pct)))

    assert clamp(0.0) == 1
    assert clamp(100.0) == 99
    assert clamp(0.4) == 1  # round(0.4)=0 → 1
    assert clamp(50.0) == 50
    assert clamp(99.6) == 99  # round=100 → 99


# ---------------- 실제 코호트 로더 ----------------
def test_load_cohort_distribution_real_artifact() -> None:
    table = load_cohort_distribution()
    assert table, "코호트 산출물이 로드돼야 한다"
    # 단일나이 창(65~79) + 80+ 풀, 성별 1/2, feature_set minimal/with_waist
    assert len(table) == 64
    assert ("minimal", 1, "72") in table  # 남성 72세 minimal
    assert ("minimal", 2, "80+") in table  # 여성 80+ 풀
    assert ("minimal", 1, "64") not in table  # 65 미만은 없다
    dist = table[("minimal", 1, "72")]
    assert len(dist.quantiles) == 101
    assert list(dist.quantiles) == sorted(dist.quantiles)  # 오름차순
    assert len(dist.density) == 101
    xs = [x for x, _ in dist.density]
    ys = [y for _, y in dist.density]
    assert xs == pytest.approx([i / 100.0 for i in range(101)])
    assert all(0.0 <= y <= 100.0 for y in ys)
    assert max(ys) == pytest.approx(100.0)
    assert dist.n > 0
    assert dist.p_low < dist.p_high

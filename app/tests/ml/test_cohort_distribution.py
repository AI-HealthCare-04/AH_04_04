"""또래 분포 차트(#193) 순수 로직: 백분위 선형보간·밀도 근사·코호트 로더."""

import pytest

from app.ml.cohort_density import (
    MODEL_DENSITY_METHOD,
    approximate_density,
    clip_density_to_domain,
)
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


# ---------------- 밀도 근사(§3, KDE 대체) ----------------
def test_approximate_density_shape() -> None:
    q = [0.5 * i / 100.0 for i in range(101)]  # 0 .. 0.5 균일
    dens = approximate_density(q, points=50)
    assert len(dens) == 50
    xs = [x for x, _ in dens]
    ys = [y for _, y in dens]
    assert xs == sorted(xs)  # x 오름차순
    assert xs[0] == 0.0 and xs[-1] == pytest.approx(0.5)
    assert min(ys) >= 0.0 and max(ys) <= 100.0 + 1e-6
    assert max(ys) == pytest.approx(100.0)  # 최대 100 정규화


def test_approximate_density_peaks_where_quantiles_dense() -> None:
    # 저확률에 분위수가 촘촘한(우편향) 분포 → 밀도 봉우리가 왼쪽에 온다.
    q = sorted(0.5 * (i / 100.0) ** 2 for i in range(101))
    dens = approximate_density(q)
    peak_x = max(dens, key=lambda p: p[1])[0]
    assert peak_x < 0.25  # 봉우리가 좌측(저확률)


def test_approximate_density_empty_on_degenerate() -> None:
    assert approximate_density([0.1, 0.1]) == []  # 3개 미만


# ---------------- 실제 코호트 로더 ----------------
def test_load_cohort_distribution_real_artifact() -> None:
    table = load_cohort_distribution()
    assert table, "코호트 산출물이 로드돼야 한다"
    # 단일나이 창(65~79) + 80+ 풀, 성별 1/2, feature_set minimal/with_waist
    assert ("minimal", 1, "72") in table  # 남성 72세 minimal
    assert ("minimal", 2, "80+") in table  # 여성 80+ 풀
    assert ("minimal", 1, "64") not in table  # 65 미만은 없다
    dist = table[("minimal", 1, "72")]
    assert len(dist.quantiles) == 101
    assert list(dist.quantiles) == sorted(dist.quantiles)  # 오름차순
    assert len(dist.density) > 0
    assert all(0.0 <= y <= 100.0 for _, y in dist.density)
    assert dist.n > 0
    assert dist.p_low < dist.p_high
    # #337: 산출물에 실제 KDE density 가 병합돼 있으므로 근사가 아닌 모델 density 를 쓴다.
    assert dist.density_method == MODEL_DENSITY_METHOD
    # 차트 표시 도메인으로 클립(x<=0.5) — 앱이 0.5 에서 클램프하므로 그 밖 점은 서버가 잘라 보낸다.
    assert all(x <= 0.5 + 1e-9 for x, _ in dist.density)
    assert max(y for _, y in dist.density) == pytest.approx(100.0)  # 상대밀도 peak=100


def test_clip_density_to_domain_keeps_only_display_range() -> None:
    dens = [[0.0, 100.0], [0.25, 60.0], [0.5, 40.0], [0.6, 30.0], [1.0, 5.0]]
    clipped = clip_density_to_domain(dens)
    assert clipped == [[0.0, 100.0], [0.25, 60.0], [0.5, 40.0]]  # x>0.5 제거, 경계 0.5 포함

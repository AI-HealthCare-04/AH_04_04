"""또래 분포 곡선용 밀도(density) 근사 — #193 위험도 차트.

앱은 KDE 를 직접 계산하지 않는다(#193 스펙 §3). 모델팀의 KDE 산출물이 아직 없어(cohort meta:
"density(곡선)는 별도 산출"), 코호트의 예측확률 분위수(quantiles q0..q100)로부터 밀도를 **서버에서
결정론적으로 근사**한다. 배포된 공용 산출물(cohort_unified_65plus.json, 기록탭 점수와 공유)을
변형하지 않으려고 json 에 병합하는 대신 로더가 quantiles 로부터 파생한다(단일 진실원천=quantiles).

원리: 분위수는 역CDF 이므로, 각 분위수 지점의 밀도는 중심차분으로 근사한다 —
    f(q_i) ~ (질량 2/100) / (q_{i+1} - q_{i-1}).
분위수가 촘촘한(간격 좁은) 구간일수록 밀도가 높다. 이를 이동평균으로 스무딩하고 균일 x격자에
선형보간해 상대밀도(최대 100 정규화)로 낸다. y축은 화면에 표시하지 않으므로(스펙 §2-7) 단위는 무의미.
"""

from __future__ import annotations

# 밀도 산출 방법 식별자(응답·문서용). 산출 방법 변경 시 버전을 올린다.
DENSITY_METHOD = "quantile_central_diff_smoothed_v1"

_MIN_GAP = 1e-4  # 간격 하한(중복 분위수로 밀도가 무한대로 튀는 것 방지)
_SMOOTH_WINDOW = 7  # 이동평균 창(홀수) — 저확률 끝 스파이크 완화
_X_CAP = 0.5  # 차트가 0.5 에서 클램프하므로(스펙 §2 gpos) 곡선도 그 부근까지만 그린다


def approximate_density(quantiles: list[float], points: int = 50) -> list[list[float]]:
    """quantiles(101개, 오름차순 예측확률) → [[x(확률), y(상대밀도 0~100)], ...] 곡선 좌표(points개).

    앱은 이 좌표로 곡선 path 를 그린다(스펙 §2). 값이 부족하면 빈 리스트.
    """
    q = [float(v) for v in quantiles]
    if len(q) < 3:
        return []
    # 1) 각 내부 분위수 지점의 중심차분 밀도. x=q[i], dens=2질량/(q[i+1]-q[i-1]).
    xs: list[float] = []
    raw: list[float] = []
    for i in range(1, len(q) - 1):
        gap = max(q[i + 1] - q[i - 1], _MIN_GAP)
        xs.append(q[i])
        raw.append((2.0 / 100.0) / gap)
    # 2) 이동평균 스무딩
    smoothed = _moving_average(raw, _SMOOTH_WINDOW)
    # 3) 균일 x격자(0 ~ min(최대분위수, cap))에 선형보간
    x_max = min(q[-1], _X_CAP)
    if x_max <= 0.0 or points < 2:
        return []
    grid = [x_max * i / (points - 1) for i in range(points)]
    ys = [_interp(x, xs, smoothed) for x in grid]
    # 4) 최대 100 으로 정규화(상대 곡선)
    peak = max(ys) if ys else 0.0
    if peak <= 0.0:
        return [[round(x, 5), 0.0] for x in grid]
    return [[round(x, 5), round(y / peak * 100.0, 2)] for x, y in zip(grid, ys, strict=True)]


def _moving_average(values: list[float], window: int) -> list[float]:
    n = len(values)
    if n == 0 or window <= 1:
        return list(values)
    half = window // 2
    out: list[float] = []
    for i in range(n):
        lo = max(0, i - half)
        hi = min(n, i + half + 1)
        seg = values[lo:hi]
        out.append(sum(seg) / len(seg))
    return out


def _interp(x: float, xs: list[float], ys: list[float]) -> float:
    """xs(오름차순) 위 선형보간. 왼쪽 밖은 첫 값(평탄), 오른쪽 밖은 0(곡선 꼬리 수렴)."""
    if not xs:
        return 0.0
    if x <= xs[0]:
        return ys[0]
    if x >= xs[-1]:
        return 0.0
    for i in range(len(xs) - 1):
        if xs[i] <= x <= xs[i + 1]:
            span = xs[i + 1] - xs[i]
            if span <= 0.0:
                return ys[i]
            t = (x - xs[i]) / span
            return ys[i] + t * (ys[i + 1] - ys[i])
    return 0.0

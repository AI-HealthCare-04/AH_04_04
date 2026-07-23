#!/usr/bin/env python
"""#131 원시 파형(CSV) → '보행 직후 앉기' 판별 특징 탐색 요약.

배경(#89 §5-5): '정상 보행 직후 곧바로 앉기'는 앉기 피크 간격(≈882ms=68보/분)이 정상 보행
대역(500~1000ms) 한가운데라 **간격이라는 단일 신호로는 분리 불가**다. 이 스크립트는
`WaveformCaptureScreen`(debug 소스셋)이 모은 라벨링된 원시 3축 CSV에서 **간격 외의 신호**
(축 방향·진폭·분산·감쇠)를 뽑아, 'normal_walk(대조군)'과 'walk_then_sit 의 앉기 구간(오탐 대상)'을
가르는 후보 특징이 실제로 분리되는지 정량 요약한다. 판별기/임계 설계의 입력이다.

사용법:
    python scripts/analyze_waveform.py <CSV들이 든 디렉토리>
    python scripts/analyze_waveform.py <디렉토리> --participants docs/waveform_participants.csv
    python scripts/analyze_waveform.py --selftest      # 합성 데이터로 파이프라인 자체검증

설계 원칙:
  - **표준 라이브러리만**(설치 불필요) — scripts/analyze_a4a_measurement.py 와 같은 컨벤션.
  - **CSV 스키마**는 docs/sensor_waveform_capture_protocol.md 부록 A(= WaveformCsv.HEADER)를 따른다.
  - **폐기 규칙(§4)**: 앉기 큐 라벨인데 cue_delivery != success 인 파일은 로드 단계에서 폐기.
    excluded=1 행(큐 직후 정착 ±300ms)은 특징·오탐 집계에서 제외.
  - **라벨로 먼저 거른다(§5 주의)**: phase 로만 거르면 안 된다. sit_only 의 phase=walking 은
    '보행'이 아니라 '앉기 전 서있기 기준선'이다. 그래서 (label, phase) 쌍으로 세그먼트를 정의한다.
  - **trial(=파일) 단위 집계 후 라벨 그룹 통계**(#166 데이터 누수 방지). 같은 파일의 인접 샘플을
    낱개로 섞어 통계 내면 표본이 부풀려진다.
  - **참여자 단위 분할(#166)**: participants 로그(trial_id→participant_id)를 주면 라벨×참여자
    교차표를 찍어, 특정 참여자에 치우쳤는지(leave-one-participant-out 가능한지)를 보여준다.
"""
import argparse
import csv
import glob
import math
import os
import statistics
import sys
import tempfile
from collections import Counter, defaultdict

# ── CSV 스키마(부록 A = WaveformCsv.HEADER) ──────────────────────────────────
HEADER = (
    "trial_id,device_model,placement_id,position,side,screen_facing,top_direction,fold_state,"
    "cue_delivery,label,phase,event,excluded,sensor_elapsed_ms,callback_elapsed_ms,"
    "x,y,z,magnitude,filtered_mag,state,count,step_counted"
).split(",")

# 라벨(= WaveformLabel.id). 앉기 큐가 있는 라벨만 cue_delivery=success 를 요구한다.
LABEL_NORMAL = "normal_walk"
LABEL_WALK_SIT = "walk_then_sit"
LABEL_SIT_ONLY = "sit_only"
LABEL_SHUFFLE = "shuffle"
CUE_LABELS = {LABEL_WALK_SIT, LABEL_SIT_ONLY}
ALL_LABELS = [LABEL_NORMAL, LABEL_WALK_SIT, LABEL_SIT_ONLY, LABEL_SHUFFLE]

# 세그먼트 = (label, phase). 관심 대비의 두 축:
#  - 오탐 대상  : (walk_then_sit, sitting)  ← 여기서 step 이 잘못 세어진다(§5-5)
#  - 대조/기준선: (normal_walk, walking)=오탐 나면 안 됨, (sit_only, sitting)=순수 앉기,
#                (sit_only, walking)=서있기, (shuffle, walking)=애매한 소진폭.
CONTROL_SEG = (LABEL_NORMAL, "walking")   # 판별 스코어카드의 음성(정상 보행)
TARGET_SEG = (LABEL_WALK_SIT, "sitting")  # 판별 스코어카드의 양성(오탐 대상 앉기)

MIN_SEG_SAMPLES = 20  # 이보다 짧은 세그먼트는 특징이 불안정 → 통계에서 제외(표시)


# ── 저수준 유틸 ──────────────────────────────────────────────────────────────
def _f(s):
    try:
        return float(s)
    except (TypeError, ValueError):
        return None


def _rms(vals):
    return math.sqrt(sum(v * v for v in vals) / len(vals)) if vals else 0.0


def load_dir(path):
    """디렉토리의 *.csv 를 모두 로드. (rows, kept, dropped) 반환.

    폐기 규칙(§4): 앉기 큐 라벨인데 cue_delivery != success → 파일 통째 폐기.
    """
    files = sorted(glob.glob(os.path.join(path, "*.csv")))
    if not files:
        sys.exit(f"CSV 가 없습니다: {path}/*.csv")
    trials, dropped = [], []
    for fp in files:
        rows = load_file(fp)
        if not rows:
            dropped.append((os.path.basename(fp), "빈 파일/헤더만"))
            continue
        label = rows[0]["label"]
        cue = rows[0]["cue_delivery"]
        if label in CUE_LABELS and cue != "success":
            dropped.append((os.path.basename(fp), f"cue_delivery={cue} (§4 폐기)"))
            continue
        trials.append({"file": os.path.basename(fp), "label": label, "rows": rows})
    return trials, dropped


def load_file(fp):
    rows = []
    with open(fp, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            if r.get("label") is None:
                continue
            r["_x"], r["_y"], r["_z"] = _f(r["x"]), _f(r["y"]), _f(r["z"])
            r["_mag"] = _f(r["magnitude"])
            r["_fmag"] = _f(r["filtered_mag"])
            r["_t"] = _f(r["sensor_elapsed_ms"])
            r["_excluded"] = (r.get("excluded") or "0").strip() == "1"
            r["_step"] = (r.get("step_counted") or "0").strip() == "1"
            if None in (r["_x"], r["_y"], r["_z"], r["_t"]):
                continue
            rows.append(r)
    return rows


# ── 특징 추출(세그먼트 = 한 파일 안의 특정 phase) ────────────────────────────
def segment_features(rows):
    """excluded 제외한 세그먼트 rows → 특징 dict. 표본 부족이면 None.

    vert_share(중력정렬 수직 에너지 비율)는 **placement 불변** 특징이다: 세그먼트 평균
    가속도 벡터를 중력 방향으로 보고, 동적 성분을 그 축(수직)과 직교면(수평)으로 나눈 뒤
    수직 에너지 점유율을 본다. 앉기는 수직(중력축) 하강이 지배적 → 높을 것이라는 가설.
    """
    rr = [r for r in rows if not r["_excluded"]]
    if len(rr) < MIN_SEG_SAMPLES:
        return None
    xs = [r["_x"] for r in rr]
    ys = [r["_y"] for r in rr]
    zs = [r["_z"] for r in rr]
    n = len(rr)
    gx, gy, gz = sum(xs) / n, sum(ys) / n, sum(zs) / n  # 중력 추정 = 평균 가속도
    gnorm = math.sqrt(gx * gx + gy * gy + gz * gz)
    if gnorm < 1e-6:
        return None
    ghx, ghy, ghz = gx / gnorm, gy / gnorm, gz / gnorm  # 중력 단위벡터

    vert_e, horiz_e = 0.0, 0.0  # 동적 성분의 수직/수평 에너지
    for r in rr:
        dx, dy, dz = r["_x"] - gx, r["_y"] - gy, r["_z"] - gz  # 동적(중력 제거) 성분
        vproj = dx * ghx + dy * ghy + dz * ghz                 # 수직 성분(스칼라)
        dmag2 = dx * dx + dy * dy + dz * dz
        vert_e += vproj * vproj
        horiz_e += max(dmag2 - vproj * vproj, 0.0)
    vert_share = vert_e / (vert_e + horiz_e) if (vert_e + horiz_e) > 0 else 0.0

    # 진폭·분산: 동적 magnitude(중력 포함 스칼라의 변동분)의 RMS, 그리고 감지기 입력 filtered_mag 산포.
    mags = [r["_mag"] for r in rr if r["_mag"] is not None]
    mmean = sum(mags) / len(mags) if mags else 0.0
    dyn_rms = _rms([m - mmean for m in mags]) if mags else 0.0
    fmags = [r["_fmag"] for r in rr if r["_fmag"] is not None]
    fmag_std = statistics.pstdev(fmags) if len(fmags) > 1 else 0.0

    # 감쇠: 세그먼트를 시간 이분 → 후반 동적에너지 / 전반 동적에너지. 보행 종료 후 잦아들면 < 1.
    half = n // 2
    e1 = _rms([r["_mag"] - mmean for r in rr[:half] if r["_mag"] is not None])
    e2 = _rms([r["_mag"] - mmean for r in rr[half:] if r["_mag"] is not None])
    decay = (e2 / e1) if e1 > 1e-6 else float("nan")

    dur_s = (rr[-1]["_t"] - rr[0]["_t"]) / 1000.0
    return {
        "n": n,
        "dur_s": round(dur_s, 2),
        "vert_share": vert_share,
        "dyn_rms": dyn_rms,
        "fmag_std": fmag_std,
        "decay": decay,
        "steps": sum(1 for r in rows if r["_step"] and not r["_excluded"]),
    }


def split_phases(rows):
    """한 파일 rows → {phase: [rows]}."""
    out = defaultdict(list)
    for r in rows:
        out[r["phase"]].append(r)
    return out


# ── 통계 헬퍼 ────────────────────────────────────────────────────────────────
def _mean(v):
    return statistics.mean(v) if v else float("nan")


def cohens_d(a, b):
    """두 표본의 표준화 평균차(분리도). |d|≥0.8 이면 큰 분리."""
    if len(a) < 2 or len(b) < 2:
        return float("nan")
    sa, sb = statistics.pstdev(a), statistics.pstdev(b)
    pooled = math.sqrt((sa * sa + sb * sb) / 2)
    if pooled < 1e-9:
        return float("inf") if _mean(a) != _mean(b) else 0.0
    return (_mean(a) - _mean(b)) / pooled


def best_threshold(neg, pos):
    """음성(neg)/양성(pos) 두 표본을 가장 잘 가르는 단일 임계와 그때 오분류 수.

    방향(양성이 임계보다 큰지/작은지)은 자동 판정. (thr, direction, errors, n) 반환.
    """
    pts = sorted(set(neg + pos))
    if len(pts) < 2:
        return None
    best = None
    for i in range(len(pts) - 1):
        thr = (pts[i] + pts[i + 1]) / 2
        # 방향1: 양성 > thr
        err_gt = sum(1 for v in pos if v <= thr) + sum(1 for v in neg if v > thr)
        # 방향2: 양성 < thr
        err_lt = sum(1 for v in pos if v >= thr) + sum(1 for v in neg if v < thr)
        if err_gt <= err_lt:
            cand = (thr, "pos>thr", err_gt)
        else:
            cand = (thr, "pos<thr", err_lt)
        if best is None or cand[2] < best[2]:
            best = cand
    return (best[0], best[1], best[2], len(neg) + len(pos))


# ── 분석 본체 ────────────────────────────────────────────────────────────────
FEATURES = [
    ("vert_share", "수직에너지 점유율", "앉기 수직지배 → 높을 것"),
    ("dyn_rms", "동적 magnitude RMS", "진폭 차"),
    ("fmag_std", "filtered_mag 표준편차", "감지기 입력 산포"),
    ("decay", "후/전반 에너지비(감쇠)", "보행 종료 감쇠 <1"),
]


def analyze(trials, participants=None):
    # trial 단위로 세그먼트 특징 계산 → (label, phase)별 리스트에 적재.
    seg_feats = defaultdict(list)  # (label, phase) -> [feat dict]
    per_label = Counter()
    short_segs = 0
    for t in trials:
        per_label[t["label"]] += 1
        for phase, prows in split_phases(t["rows"]).items():
            fe = segment_features(prows)
            if fe is None:
                short_segs += 1
                continue
            fe["file"] = t["file"]
            seg_feats[(t["label"], phase)].append(fe)

    print(f"분석 대상: {len(trials)} trial")
    for lb in ALL_LABELS:
        if per_label[lb]:
            print(f"  · {lb:14s} {per_label[lb]:3d} trial")
    if short_segs:
        print(f"  (표본 <{MIN_SEG_SAMPLES} 라 제외된 세그먼트: {short_segs}개)")
    print()

    # ── 1. 오탐 현황: walk_then_sit 의 앉기 구간에서 몇 걸음이 잘못 세어지나(§5-5 재현) ──
    print("■ 오탐 현황 — 앉기(sitting) 구간의 잘못 카운트된 걸음 (수용기준: walk_then_sit ≤ +2)")
    for lb in (LABEL_WALK_SIT, LABEL_SIT_ONLY):
        segs = seg_feats.get((lb, "sitting"), [])
        if not segs:
            print(f"  · {lb:14s} sitting: (데이터 없음)")
            continue
        steps = [s["steps"] for s in segs]
        flag = "  ⚠️ 목표(≤2) 초과" if lb == LABEL_WALK_SIT and _mean(steps) > 2 else ""
        print(f"  · {lb:14s} sitting: 평균 {_mean(steps):.1f} 걸음/trial "
              f"(범위 {min(steps)}~{max(steps)}, n={len(segs)}){flag}")
    # 대조군은 walking 구간이지만 정상 보행이므로 카운트가 '나는 게' 정상 → 참고로만.
    print()

    # ── 2. 세그먼트 특징 표(trial 단위 평균) ──
    print("■ 세그먼트별 특징 평균 (trial 단위)")
    print(f"  {'label / phase':24s} {'vert_share':>10s} {'dyn_rms':>9s} {'fmag_std':>9s} {'decay':>7s}")
    for lb in ALL_LABELS:
        for phase in ("walking", "sitting"):
            segs = seg_feats.get((lb, phase), [])
            if not segs:
                continue
            vs = _mean([s["vert_share"] for s in segs])
            dr = _mean([s["dyn_rms"] for s in segs])
            fs = _mean([s["fmag_std"] for s in segs])
            dc = _mean([s["decay"] for s in segs if not math.isnan(s["decay"])])
            print(f"  {lb+' / '+phase:24s} {vs:10.3f} {dr:9.3f} {fs:9.3f} {dc:7.2f}")
    print()

    # ── 3. 판별 스코어카드: 정상보행(음성) vs 보행직후앉기(양성) ──
    neg_segs = seg_feats.get(CONTROL_SEG, [])
    pos_segs = seg_feats.get(TARGET_SEG, [])
    print(f"■ 판별 스코어카드 — 음성={CONTROL_SEG[0]}/{CONTROL_SEG[1]} (n={len(neg_segs)}) "
          f"vs 양성={TARGET_SEG[0]}/{TARGET_SEG[1]} (n={len(pos_segs)})")
    if not neg_segs or not pos_segs:
        print("  두 그룹 중 하나가 비어 스코어카드를 낼 수 없습니다(수집 후 재실행).")
    else:
        print(f"  {'특징':22s} {'음성μ':>8s} {'양성μ':>8s} {'Cohen d':>8s} {'추천임계':>10s} {'오분류':>10s}")
        ranked = []
        for key, _name, _hint in FEATURES:
            neg = [s[key] for s in neg_segs if not math.isnan(s[key])]
            pos = [s[key] for s in pos_segs if not math.isnan(s[key])]
            if len(neg) < 2 or len(pos) < 2:
                continue
            d = cohens_d(neg, pos)
            bt = best_threshold(neg, pos)
            thr_s = f"{bt[0]:.3f}{'>' if bt[1]=='pos>thr' else '<'}" if bt else "n/a"
            err_s = f"{bt[2]}/{bt[3]}" if bt else "n/a"
            print(f"  {key:22s} {_mean(neg):8.3f} {_mean(pos):8.3f} {d:8.2f} {thr_s:>10s} {err_s:>10s}")
            ranked.append((abs(d) if not math.isnan(d) else 0, key, d))
        ranked.sort(reverse=True)
        if ranked:
            top = ranked[0]
            print(f"\n  → 가장 분리도 큰 특징: {top[1]} (|d|={top[0]:.2f}). "
                  f"{'유망(|d|≥0.8)' if top[0] >= 0.8 else '단독으론 약함 → 특징 조합/추가센서 검토'}")
    print()

    # ── 4. 참여자 분할 안내(#166) ──
    print("■ 참여자 단위 분할(#166 데이터 누수 방지)")
    if participants:
        cross = defaultdict(Counter)
        unknown = 0
        for t in trials:
            pid = participants.get(t["file"]) or participants.get(t["rows"][0]["trial_id"])
            if pid is None:
                unknown += 1
                continue
            cross[pid][t["label"]] += 1
        for pid in sorted(cross):
            dist = ", ".join(f"{lb}:{cross[pid][lb]}" for lb in ALL_LABELS if cross[pid][lb])
            print(f"  · {pid}: {dist}")
        print(f"  참여자 {len(cross)}명 → leave-one-participant-out {'가능' if len(cross) >= 2 else '불가(1명, 추가 수집 필요)'}")
        if unknown:
            print(f"  ⚠️ 참여자 로그에 없는 trial {unknown}개 — 로그를 채우세요.")
    else:
        print("  참여자 로그 미제공(--participants). CSV 에는 participant_id 컬럼이 없으므로(부록 A),")
        print("  train/eval 을 참여자 단위로 나누려면 trial_id→participant_id 로그가 필요합니다.")
    print()


def load_participants(path):
    """trial_id,participant_id[,session_id] CSV → {trial_id: participant_id}."""
    m = {}
    with open(path, encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            tid = (r.get("trial_id") or "").strip()
            pid = (r.get("participant_id") or "").strip()
            if tid and pid:
                m[tid] = pid
                m[tid + ".csv"] = pid  # 파일명으로도 매칭
    return m


# ── 합성 데이터 자체검증(--selftest) ─────────────────────────────────────────
def _synth_file(fp, label, placement, gravity_axis="z"):
    """합성 CSV 1개 생성. 보행=수평(전후) 진동, 앉기=수직(중력축) 하강 임팩트.

    파이프라인이 (a) 폐기규칙 (b) (label,phase) 세그먼트화 (c) vert_share 등 특징을
    올바르게 뽑는지 검증하기 위한 것. 실제 파형이 아니라 특성만 흉내낸다.
    """
    import random
    rnd = random.Random(hash((fp, label)) & 0xFFFF)
    g = {"x": (9.8, 0, 0), "y": (0, 9.8, 0), "z": (0, 0, 9.8)}[gravity_axis]
    hz, dt = 50, 20  # 50Hz
    rows = [",".join(HEADER)]
    has_cue = label in CUE_LABELS
    walk_s = 15 if has_cue else 20
    sit_s = 5 if has_cue else 0
    t = 0

    def emit(phase, event, excluded, dyn, step):
        nonlocal t
        # dyn = (dvx, dvy, dvz) 동적 성분. 관측 = 중력 + 동적 + 소잡음.
        x = g[0] + dyn[0] + rnd.uniform(-0.05, 0.05)
        y = g[1] + dyn[1] + rnd.uniform(-0.05, 0.05)
        z = g[2] + dyn[2] + rnd.uniform(-0.05, 0.05)
        mag = math.sqrt(x * x + y * y + z * z)
        state = "WALKING" if (phase == "walking" and label in (LABEL_NORMAL, LABEL_WALK_SIT)) else "IDLE"
        rows.append(",".join(str(v) for v in [
            os.path.basename(fp).replace(".csv", ""), "SM-F766N", placement,
            "front_pocket", "right", "in", "up", "folded",
            "success" if has_cue else "na", label, phase, event, 1 if excluded else 0,
            t, t, round(x, 5), round(y, 5), round(z, 5), round(mag, 5), round(mag, 5),
            state, 0, 1 if step else 0,
        ]))
        t += dt

    # 보행/서있기/발끌기 구간
    for i in range(walk_s * hz):
        ph = math.sin(2 * math.pi * 1.7 * i / hz)  # ~1.7Hz 스텝
        if label in (LABEL_NORMAL, LABEL_WALK_SIT):     # 보행: 전후(수평) 진동 큼 + 수직 약간
            horiz = (1.6 * ph, 0.4 * ph, 0)
            dyn = _rot(horiz, gravity_axis)
            step = (i % (hz // 2) == 0)                  # 초당 ~2걸음 카운트
        elif label == LABEL_SHUFFLE:                     # 발끌기: 소진폭 수평
            dyn = _rot((0.3 * ph, 0.2 * ph, 0), gravity_axis)
            step = False
        else:                                            # sit_only: 서있기(거의 정지)
            dyn = (rnd.uniform(-0.03, 0.03), rnd.uniform(-0.03, 0.03), rnd.uniform(-0.03, 0.03))
            step = False
        emit("walking", "sit_cue" if False else "", False, dyn, step)

    if has_cue:
        for i in range(sit_s * hz):
            # 앉기: 수직(중력축) 하강 임팩트 — 초반 큰 수직 진폭 후 감쇠.
            env = math.exp(-3.0 * i / (sit_s * hz))
            vert = 2.5 * env * math.sin(2 * math.pi * 1.1 * i / hz)
            dyn = _rot((0.1, 0.1, vert), gravity_axis)  # 수직 성분 지배
            excluded = i < int(0.3 * hz)                # 큐 직후 300ms 정착 배제
            step = (not excluded) and (i % (hz // 2) == 0) and i < hz * 3  # 앉기 오탐 몇 개
            emit("sitting", "sit_cue" if i == 0 else "", excluded, dyn, step)

    with open(fp, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(rows) + "\n")


def _rot(v, gravity_axis):
    """동적 성분 (수평0,수평1,수직) 을 중력축이 gravity_axis 가 되도록 회전."""
    h0, h1, ver = v
    if gravity_axis == "z":
        return (h0, h1, ver)
    if gravity_axis == "x":
        return (ver, h0, h1)
    return (h0, ver, h1)  # y


def selftest():
    print("=== --selftest: 합성 파형으로 파이프라인 검증 ===\n")
    d = tempfile.mkdtemp(prefix="wf_selftest_")
    plan = [
        (LABEL_NORMAL, "z", 4), (LABEL_WALK_SIT, "z", 4),
        (LABEL_SIT_ONLY, "z", 3), (LABEL_SHUFFLE, "z", 3),
        (LABEL_WALK_SIT, "x", 2),  # 다른 중력축(placement) — vert_share 불변성 확인용
    ]
    n = 0
    for label, axis, cnt in plan:
        for i in range(cnt):
            _synth_file(os.path.join(d, f"{label}_2026010100000{n:02d}.csv"), label, f"prst_{axis}", axis)
            n += 1
    # 폐기 규칙 검증용: cue_delivery 실패 파일 1개(walk_then_sit 인데 na)
    bad = os.path.join(d, "walk_then_sit_bad.csv")
    _synth_file(bad, LABEL_WALK_SIT, "prst_z", "z")
    with open(bad, encoding="utf-8") as f:
        txt = f.read().replace(",success,", ",pending,")
    with open(bad, "w", encoding="utf-8") as f:
        f.write(txt)

    trials, dropped = load_dir(d)
    assert any("§4 폐기" in r for _, r in dropped), "cue 실패 파일이 폐기되지 않음"
    assert len(trials) == n, f"trial 수 불일치: {len(trials)} != {n}"

    # 핵심 특징 검증: 앉기(sitting) 세그먼트의 vert_share 가 보행(walking)보다 높아야 한다.
    def seg_vs(label, phase, axis=None):
        vals = []
        for t in trials:
            if t["label"] != label:
                continue
            if axis and not t["rows"][0]["placement_id"].endswith(axis):
                continue
            fe = segment_features(split_phases(t["rows"])[phase])
            if fe:
                vals.append(fe["vert_share"])
        return _mean(vals)

    vs_walk = seg_vs(LABEL_NORMAL, "walking")
    vs_sit = seg_vs(LABEL_WALK_SIT, "sitting")
    vs_sit_x = seg_vs(LABEL_WALK_SIT, "sitting", "x")
    print(f"[검증] normal_walk/walking vert_share = {vs_walk:.3f}")
    print(f"[검증] walk_then_sit/sitting vert_share = {vs_sit:.3f}")
    print(f"[검증] walk_then_sit/sitting vert_share (중력축=x) = {vs_sit_x:.3f}  ← placement 불변이면 z와 비슷")
    assert vs_sit > vs_walk + 0.2, "앉기 수직지배 특징이 재현 안 됨"
    assert abs(vs_sit_x - vs_sit) < 0.2, "vert_share 가 placement(중력축)에 의존 — 불변성 실패"
    print("\n[✓] 폐기규칙·세그먼트화·vert_share(placement 불변) 모두 통과\n")

    # 참여자 로그 조인 경로 검증: trial 을 P01/P02 로 매핑한 로그를 써서 읽는다.
    plog = os.path.join(d, "participants.csv")
    with open(plog, "w", encoding="utf-8", newline="") as f:
        f.write("trial_id,participant_id,session_id\n")
        for i, t in enumerate(trials):
            pid = "P01" if i % 2 == 0 else "P02"
            f.write(f"{t['rows'][0]['trial_id']},{pid},{pid}_s1\n")
    parts = load_participants(plog)
    assert parts and all(p in ("P01", "P02") for p in parts.values()), "참여자 로그 조인 실패"
    print("[✓] 참여자 로그 조인 경로 통과\n")

    # 단일 파일 점검(--inspect) 경로도 태운다.
    sample_file = glob.glob(os.path.join(d, "normal_walk_*.csv"))[0]
    assert inspect(sample_file) is True, "정상 합성 파일이 inspect 에서 FAIL"
    print("[✓] --inspect 단일 파일 점검 경로 통과\n")

    print("=== 합성 데이터에 대한 전체 리포트(형식 확인용) ===\n")
    analyze(trials, parts)
    print("[✓] selftest 통과 — 실제 수집 CSV 로 `python scripts/analyze_waveform.py <디렉토리>` 실행하세요.")


# ── 단일 파일 sanity check(--inspect) ────────────────────────────────────────
def inspect(path):
    """수집 직후 CSV 1개가 '제대로 담겼는지' 즉시 판정(현장 검증용).

    각 항목을 ✅/⚠️/❌ 로 표시하고 마지막에 '분석 사용 가능/폐기' 종합 판정을 낸다.
    ❌ 가 하나라도 있으면 그 파일은 재수집 대상이다.
    """
    print(f"=== 파일 점검: {os.path.basename(path)} ===\n")
    ok = True

    # 1) 헤더 스키마(부록 A 23컬럼)
    with open(path, encoding="utf-8-sig", newline="") as f:
        fields = (csv.DictReader(f).fieldnames) or []
    if fields == HEADER:
        print("  ✅ 헤더: 23컬럼 스키마 일치")
    else:
        ok = False
        print(f"  ❌ 헤더 불일치: {len(fields)}컬럼(기대 {len(HEADER)}) — 앱 버전/파일 손상 확인")

    rows = load_file(path)
    n = len(rows)
    if n == 0:
        print("  ❌ 데이터 행 0개 — 수집 실패\n\n판정: ❌ 사용 불가(재수집)")
        return
    dur = (rows[-1]["_t"] - rows[0]["_t"]) / 1000.0
    hz = n / dur if dur > 0 else 0

    # 2) 행수·지속시간·샘플레이트
    dur_ok = dur >= 3
    print(f"  {'✅' if dur_ok else '⚠️'} 샘플 {n}행 · {dur:.1f}초 · ≈{hz:.0f}Hz"
          + ("" if dur_ok else "  (너무 짧음 — 중단/조기종료 의심)"))

    # 3) 라벨 일관성
    labels = {r["label"] for r in rows}
    label = rows[0]["label"]
    if labels == {label} and label in ALL_LABELS:
        print(f"  ✅ 라벨: {label} (전 행 일관)")
    else:
        ok = False
        print(f"  ❌ 라벨 이상: {labels}")

    # 4) cue_delivery (§4 폐기 판정)
    cue = rows[0]["cue_delivery"]
    if label in CUE_LABELS:
        if cue == "success":
            print("  ✅ cue_delivery=success (앉기 큐 전달됨)")
        else:
            ok = False
            print(f"  ❌ cue_delivery={cue} — 비프 미전달 → §4 폐기 대상")
    else:
        print(f"  {'✅' if cue == 'na' else '⚠️'} cue_delivery={cue} (큐 없는 라벨은 na 정상)")

    # 5) phase / sit_cue 마커 / excluded
    phases = Counter(r["phase"] for r in rows)
    cues = sum(1 for r in rows if r["event"] == "sit_cue")
    excl = sum(1 for r in rows if r["_excluded"])
    if label in CUE_LABELS:
        seg_ok = cues == 1 and phases.get("sitting", 0) > 0
        print(f"  {'✅' if seg_ok else '⚠️'} 구간: walking {phases.get('walking', 0)} / "
              f"sitting {phases.get('sitting', 0)} · sit_cue 마커 {cues}개(기대 1) · excluded {excl}")
    else:
        seg_ok = phases.get("sitting", 0) == 0 and cues == 0
        print(f"  {'✅' if seg_ok else '⚠️'} 구간: walking {phases.get('walking', 0)}행"
              + ("" if seg_ok else " · 큐 없는 라벨인데 sitting/sit_cue 존재"))

    # 6) 원시 3축이 실제로 변동하는가(센서 미동작/폰 고정 감지)
    sx = statistics.pstdev([r["_x"] for r in rows])
    sy = statistics.pstdev([r["_y"] for r in rows])
    sz = statistics.pstdev([r["_z"] for r in rows])
    mags = [r["_mag"] for r in rows if r["_mag"] is not None]
    mmean = sum(mags) / len(mags) if mags else 0.0
    if max(sx, sy, sz) < 0.03:
        ok = False
        print(f"  ❌ 3축이 거의 정지: std={sx:.2f}/{sy:.2f}/{sz:.2f} — 센서 미동작/폰 고정 의심")
    else:
        print(f"  ✅ 3축 변동: std(x/y/z)={sx:.2f}/{sy:.2f}/{sz:.2f} · |a|평균 {mmean:.2f}(≈9.8 중력)")

    # 7) 걸음 카운트 기록(보행 라벨은 걸음이 잡혀야 자연스러움 — 감지기 특성이라 WARN)
    steps = sum(1 for r in rows if r["_step"])
    walking_rows = sum(1 for r in rows if r["state"] == "WALKING")
    try:
        last_count = int(rows[-1]["count"])
    except (ValueError, TypeError):
        last_count = -1
    if label in (LABEL_NORMAL, LABEL_WALK_SIT):
        print(f"  {'✅' if steps > 0 else '⚠️'} 걸음: 감지기 count={last_count} · "
              f"step_counted {steps}회 · WALKING 상태 {100 * walking_rows // n}%"
              + ("" if steps > 0 else "  (보행 라벨인데 걸음 0 — 느리거나 배치 문제 가능)"))
    else:
        print(f"  · 걸음: count={last_count} · step_counted {steps}회 (앉기/발끌기는 낮은 게 정상)")

    # 8) 시간축 단조 증가
    ts = [r["_t"] for r in rows]
    mono = all(ts[i] <= ts[i + 1] for i in range(len(ts) - 1))
    print(f"  {'✅' if mono else '⚠️'} sensor_elapsed_ms 단조 증가: {'예' if mono else '아니오'}")

    print(f"\n판정: {'✅ 분석에 사용 가능' if ok else '❌ 문제 있음 — 위 ❌ 항목 확인 후 재수집'}")
    return ok


def main():
    ap = argparse.ArgumentParser(description="#131 파형 판별 특징 탐색")
    ap.add_argument("data_dir", nargs="?", help="수집 CSV 들이 든 디렉토리")
    ap.add_argument("--participants", help="trial_id,participant_id[,session_id] 로그 CSV")
    ap.add_argument("--selftest", action="store_true", help="합성 데이터로 파이프라인 자체검증")
    ap.add_argument("--inspect", metavar="FILE", help="수집 CSV 1개가 제대로 담겼는지 즉시 점검")
    args = ap.parse_args()

    if args.selftest:
        selftest()
        return
    if args.inspect:
        sys.exit(0 if inspect(args.inspect) else 1)
    if not args.data_dir:
        ap.error("data_dir 를 주거나 --selftest 를 쓰세요.")
    parts = load_participants(args.participants) if args.participants else None
    trials, dropped = load_dir(args.data_dir)
    if dropped:
        print(f"■ 폐기된 파일 {len(dropped)}개 (§4)")
        for name, reason in dropped:
            print(f"  · {name}: {reason}")
        print()
    if not trials:
        sys.exit("남은 trial 이 없습니다(모두 폐기됨). cue_delivery/수집 상태를 확인하세요.")
    analyze(trials, parts)


if __name__ == "__main__":
    main()

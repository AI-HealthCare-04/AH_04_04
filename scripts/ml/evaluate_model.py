"""
근감소증 모델 성능 평가·재현 스크립트 (심사 3-1 정량 근거).

실행하면 아래를 산출하고 metrics.json / metrics.csv 로 저장한다:
  - minimal / with_waist (일수 기반) 5-fold OOF: AUROC · AUPRC · Brier · ECE(10 bins)
    + fold별 값 + 평균±표준편차 + 부트스트랩 95% CI
  - 비교 실험: (1) days vs binary  (2) LR vs 트리  (3) 비가중 vs 표집가중  (4) 5-fold vs 80/20
  - AUPRC는 유병률 기준선(0.1327)과 함께 해석
모든 난수 random_state=42 고정.

데이터: KNHANES 2022-2024 처리본(sarcopenia_dataset.parquet + _2024.parquet).
  ※ 저작권상 저장소에 원자료를 두지 않는다. --data_dir 로 경로를 준다.
  ※ 데이터 없이도 결과를 볼 수 있게, 본 스크립트로 생성한 metrics.json/csv 를 함께 커밋한다.

AWGS 2025 라벨은 저장 컬럼(2019)이 아니라 컴포넌트에서 재계산한다:
  저근육량 = ASMI<7.0(남)/5.7(여) OR ASM/BMI<0.83(남)/0.57(여)
  저악력   = grip<28(남)/18(여);  라벨 = 저근육량 AND 저악력
"""
from __future__ import annotations
import argparse, json, csv
from pathlib import Path
import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.pipeline import Pipeline
from sklearn.impute import SimpleImputer
from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.model_selection import StratifiedKFold, train_test_split
from sklearn.metrics import roc_auc_score, average_precision_score, brier_score_loss

SEED = 42
BASELINE_LABEL = "sarcopenia_awgs2025 (recomputed)"


# ---------- data ----------
def load(data_dir: Path) -> pd.DataFrame:
    d = pd.concat([pd.read_parquet(data_dir / "sarcopenia_dataset.parquet"),
                   pd.read_parquet(data_dir / "sarcopenia_dataset_2024.parquet")],
                  ignore_index=True).copy()
    d["walk_days"] = (d["walking_days_code"].where(~d["walking_days_code"].isin([99])) - 1).clip(0, 7)
    d["musc_days"] = (d["strength_exercise_code"].where(~d["strength_exercise_code"].isin([9])) - 1).clip(0, 5)
    d["age_clip"] = d["age"].clip(upper=80)
    male = d["sex"] == 1
    low_asmi = d["ASMI_BIA"] < np.where(male, 7.0, 5.7)
    low_asmbmi = (d["ASM_BIA"] / d["bmi"]) < np.where(male, 0.83, 0.57)
    low_grip = d["max_handgrip"] < np.where(male, 28, 18)
    d["y2025"] = ((low_asmi | low_asmbmi) & low_grip).astype(float)
    valid = d["max_handgrip"].notna() & d["ASMI_BIA"].notna() & d["ASM_BIA"].notna() & d["bmi"].notna()
    return d[valid].copy()


FEATURES = {
    "minimal": ["age_clip", "sex", "height_cm", "weight_kg", "bmi", "walk_days", "musc_days"],
    "with_waist": ["age_clip", "sex", "height_cm", "weight_kg", "bmi", "waist_cm", "walk_days", "musc_days"],
    "minimal_binary": ["age_clip", "sex", "height_cm", "weight_kg", "bmi", "pa_walk_30min_5days", "pa_muscle_2days"],
}


def make_model(cols, kind="lr"):
    num = [c for c in cols if c != "sex"]
    if kind == "lr":
        pre = ColumnTransformer([
            ("num", Pipeline([("i", SimpleImputer(strategy="median")), ("s", StandardScaler())]), num),
            ("bin", SimpleImputer(strategy="most_frequent"), ["sex"]),
        ])
        return Pipeline([("pre", pre), ("lr", LogisticRegression(max_iter=3000, random_state=SEED))])
    pre = ColumnTransformer([("num", SimpleImputer(strategy="median"), num),
                             ("bin", SimpleImputer(strategy="most_frequent"), ["sex"])])
    return Pipeline([("pre", pre), ("tree", HistGradientBoostingClassifier(random_state=SEED))])


# ---------- metrics ----------
def ece(y, p, bins=10):
    edges = np.linspace(0, 1, bins + 1)
    idx = np.clip(np.digitize(p, edges) - 1, 0, bins - 1)
    e = 0.0
    for b in range(bins):
        m = idx == b
        if m.any():
            e += m.mean() * abs(y[m].mean() - p[m].mean())
    return float(e)


def metric_set(y, p, w=None):
    if w is None:
        return dict(auroc=float(roc_auc_score(y, p)),
                    auprc=float(average_precision_score(y, p)),
                    brier=float(brier_score_loss(y, p)),
                    ece=ece(y, p))
    w = w / w.mean()
    br = float(np.average((p - y) ** 2, weights=w))
    # weighted ECE
    edges = np.linspace(0, 1, 11); idx = np.clip(np.digitize(p, edges) - 1, 0, 9); e = 0.0; W = w.sum()
    for b in range(10):
        m = idx == b
        if m.any():
            wm = w[m].sum()
            e += (wm / W) * abs(np.average(y[m], weights=w[m]) - np.average(p[m], weights=w[m]))
    return dict(auroc=float(roc_auc_score(y, p, sample_weight=w)),
                auprc=float(average_precision_score(y, p, sample_weight=w)),
                brier=br, ece=float(e))


def boot_ci(y, p, fn, n=1000):
    rng = np.random.default_rng(SEED); vals = []
    idx = np.arange(len(y))
    for _ in range(n):
        s = rng.choice(idx, len(idx), replace=True)
        try: vals.append(fn(y[s], p[s]))
        except Exception: pass
    lo, hi = np.percentile(vals, [2.5, 97.5])
    return round(float(lo), 4), round(float(hi), 4)


def oof(df, cols, kind="lr"):
    y = df["y2025"].values.astype(int)
    X = df[cols]
    skf = StratifiedKFold(5, shuffle=True, random_state=SEED)
    p = np.zeros(len(y)); folds = []
    for tr, te in skf.split(X, y):
        mdl = make_model(cols, kind).fit(X.iloc[tr], y[tr])
        p[te] = mdl.predict_proba(X.iloc[te])[:, 1]
        folds.append(metric_set(y[te], p[te]))
    fk = {k: [f[k] for f in folds] for k in folds[0]}
    return y, p, folds, fk


def holdout(df, cols, kind="lr"):
    y = df["y2025"].values.astype(int); X = df[cols]
    Xtr, Xte, ytr, yte = train_test_split(X, y, test_size=0.2, stratify=y, random_state=SEED)
    mdl = make_model(cols, kind).fit(Xtr, ytr)
    p = mdl.predict_proba(Xte)[:, 1]
    return metric_set(yte, p)


# ---------- run ----------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data_dir", required=True)
    ap.add_argument("--out_dir", default=".")
    a = ap.parse_args()
    df = load(Path(a.data_dir))
    prev = float(df["y2025"].mean()); n = len(df)
    out = {"meta": {"label": BASELINE_LABEL, "n": n, "prevalence": round(prev, 4),
                    "auprc_baseline": round(prev, 4), "seed": SEED,
                    "cv": "StratifiedKFold(5, shuffle, seed=42)", "ece_bins": 10}}

    # 1) headline: minimal / with_waist (일수) 5-fold OOF
    head = {}
    for fs in ["minimal", "with_waist"]:
        sub = df.dropna(subset=["waist_cm"]) if fs == "with_waist" else df
        y, p, folds, fk = oof(sub, FEATURES[fs], "lr")
        m = metric_set(y, p)
        head[fs] = {"n": len(sub), "prevalence": round(float(y.mean()), 4),
                    "auroc": round(m["auroc"], 4), "auprc": round(m["auprc"], 4),
                    "brier": round(m["brier"], 4), "ece": round(m["ece"], 4),
                    "auroc_ci95": boot_ci(y, p, lambda a, b: roc_auc_score(a, b)),
                    "auprc_ci95": boot_ci(y, p, lambda a, b: average_precision_score(a, b)),
                    "fold_auroc_mean_std": [round(np.mean(fk["auroc"]), 4), round(np.std(fk["auroc"]), 4)],
                    "fold_auprc_mean_std": [round(np.mean(fk["auprc"]), 4), round(np.std(fk["auprc"]), 4)],
                    "folds": [{k: round(v, 4) for k, v in f.items()} for f in folds]}
    out["headline_5fold_oof"] = head

    # 2) days vs binary (minimal)
    cmp = {}
    yb, pb, _, _ = oof(df, FEATURES["minimal_binary"], "lr")
    yd, pd_, _, _ = oof(df, FEATURES["minimal"], "lr")
    cmp["days_vs_binary_minimal"] = {
        "days":   {k: round(v, 4) for k, v in metric_set(yd, pd_).items()},
        "binary": {k: round(v, 4) for k, v in metric_set(yb, pb).items()}}

    # 3) LR vs tree (minimal days)
    yt, pt, _, _ = oof(df, FEATURES["minimal"], "tree")
    cmp["lr_vs_tree_minimal_days"] = {
        "lr":   {k: round(v, 4) for k, v in metric_set(yd, pd_).items()},
        "tree": {k: round(v, 4) for k, v in metric_set(yt, pt).items()}}

    # 4) unweighted vs survey-weighted (minimal days, OOF preds)
    w = df["wt_hs"].values.astype(float)
    cmp["unweighted_vs_survey_weighted_minimal_days"] = {
        "unweighted": {k: round(v, 4) for k, v in metric_set(yd, pd_).items()},
        "survey_weighted_wt_hs": {k: round(v, 4) for k, v in metric_set(yd, pd_, w=w).items()},
        "note": "wt_hs (health-survey weight) normalized to mean 1; scale-invariant for AUROC/AUPRC"}

    # 5) 5-fold vs 80/20 (minimal days)
    cmp["cv5_vs_holdout8020_minimal_days"] = {
        "cv5_oof": {k: round(v, 4) for k, v in metric_set(yd, pd_).items()},
        "holdout_8020": {k: round(v, 4) for k, v in holdout(df, FEATURES["minimal"], "lr").items()}}

    out["comparisons"] = cmp

    outdir = Path(a.out_dir); outdir.mkdir(parents=True, exist_ok=True)
    json.dump(out, open(outdir / "metrics.json", "w"), ensure_ascii=False, indent=2)
    # flat csv
    rows = []
    for fs, v in head.items():
        rows.append({"block": "headline_5fold_oof", "variant": fs, "n": v["n"],
                     "auroc": v["auroc"], "auprc": v["auprc"], "brier": v["brier"], "ece": v["ece"]})
    for block, d in cmp.items():
        for variant, m in d.items():
            if isinstance(m, dict) and "auroc" in m:
                rows.append({"block": block, "variant": variant, "n": n,
                             "auroc": m["auroc"], "auprc": m["auprc"], "brier": m["brier"], "ece": m["ece"]})
    with open(outdir / "metrics.csv", "w", newline="") as f:
        wtr = csv.DictWriter(f, fieldnames=["block", "variant", "n", "auroc", "auprc", "brier", "ece"])
        wtr.writeheader(); [wtr.writerow(r) for r in rows]

    print(f"label prevalence={prev:.4f} n={n}")
    print("HEADLINE 5-fold OOF:")
    for fs, v in head.items():
        print(f"  {fs:11s} AUROC {v['auroc']} AUPRC {v['auprc']} Brier {v['brier']} ECE {v['ece']} (n={v['n']})")
    print("wrote metrics.json / metrics.csv")


if __name__ == "__main__":
    main()

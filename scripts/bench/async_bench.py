"""심사 3-2 — 추론 비동기 처리(run_in_executor)의 효과 측정.

## 무엇을 재는가

`app/ml/predictor.py` 의 `RiskPredictor.predict()` 는 블로킹 sklearn 추론을
`loop.run_in_executor()` 로 스레드풀에 넘긴다. 이 스크립트는 그 설계 결정의 효과를 측정한다.

**단일 호출 지연을 재면 안 된다.** 스레드풀 디스패치 오버헤드 때문에 비동기 쪽이 오히려
느리게 나오고, 그 숫자는 이 설계의 목적과 무관하다. 비동기 경계의 목적은 한 요청을 빠르게
만드는 것이 아니라 **한 요청이 이벤트 루프를 점유해 다른 모든 요청을 막는 것을 방지**하는 것이다.

그래서 이 벤치는 예측 엔드포인트에 동시 부하를 거는 **동안**, 아무 일도 하지 않는 가벼운
엔드포인트(`/health`)의 응답 지연을 관측한다. 이것이 이벤트 루프 점유가 실제로 해치는 값이다.

  조건 A (baseline) : 부하 없음. /health 만 호출 → 정상 상태 기준선
  조건 B (blocking) : 예측을 async 라우트에서 직접 호출(= 이벤트 루프 점유)
  조건 C (executor) : 예측을 run_in_executor 로 위임(= 현재 배포 구현)

기대: B 에서 /health 의 꼬리 지연(P95/P99)이 치솟고, C 에서는 A 에 가깝게 유지된다.

## 범위

DB·인증을 거치지 않고 추론 경계만 격리해 측정한다. 실제 라우트에는 async DB I/O 가 함께
있지만 그쪽은 이미 논블로킹이라 이 비교의 결론을 바꾸지 않으며, 오히려 DB 지연이 섞이면
재려는 신호가 묻힌다. 전체 스택의 P95 는 심사 5-1(부하 테스트)의 범위다.

프로덕션 코드는 수정하지 않는다. 아래 두 라우트는 이 스크립트가 소유하는 비교용이며,
실제 서비스 라우터에는 존재하지 않는다.

## 실행

    uv run --no-sync python scripts/bench/async_bench.py

결과는 stdout 표 + `scripts/bench/async_bench_result.json` 으로 남는다.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import statistics
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
# 스크립트로 직접 실행할 때도 `app` 패키지를 찾을 수 있게 한다(pytest 는 pyproject 설정으로 해결됨).
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

HOST = "127.0.0.1"
PORT = 8899
BASE = f"http://{HOST}:{PORT}"

# 결정성 테스트와 같은 고정 입력(나이 고정 → 시계 비의존).
FEATURES = {
    "age": 72,
    "sex": 1,
    "height_cm": 168,
    "weight_kg": 68,
    "walk_days": 3,
    "musc_days": 1,
}


# ────────────────────────────── 서버 ──────────────────────────────
def build_app():
    """비교용 ASGI 앱. 실제 추론기(RiskPredictor)를 그대로 사용한다."""
    from fastapi import FastAPI

    from app.ml.predictor import RiskPredictor

    predictor = RiskPredictor()
    predictor.predict_sync(FEATURES)  # 모델 로드·BLAS 워밍업을 요청 경로 밖에서 끝낸다

    app = FastAPI()

    @app.get("/health")
    async def health() -> dict[str, bool]:
        # 이벤트 루프가 한가하면 즉시 반환되어야 하는 참조 엔드포인트.
        return {"ok": True}

    @app.post("/predict-blocking")
    async def predict_blocking() -> dict[str, float]:
        # 비교군: await 없이 동기 추론을 그대로 호출 → 그동안 이벤트 루프가 멈춘다.
        r = predictor.predict_sync(FEATURES)
        return {"risk_score": r.risk_score}

    @app.post("/predict-executor")
    async def predict_executor() -> dict[str, float]:
        # 현재 배포 구현과 동일한 경로(run_in_executor).
        r = await predictor.predict(FEATURES)
        return {"risk_score": r.risk_score}

    return app


def serve() -> None:
    import uvicorn

    # workers=1: 이벤트 루프 하나에서의 거동을 보는 것이 목적이다.
    uvicorn.run(build_app(), host=HOST, port=PORT, log_level="error", workers=1)


# ────────────────────────────── 측정 ──────────────────────────────
def pct(values: list[float], q: float) -> float:
    if not values:
        return float("nan")
    ordered = sorted(values)
    idx = min(int(len(ordered) * q), len(ordered) - 1)
    return ordered[idx]


async def probe_health(client, stop: asyncio.Event, out: list[float], interval: float) -> None:
    """부하와 무관하게 일정 간격으로 /health 를 두드려 지연을 기록한다."""
    while not stop.is_set():
        t0 = time.perf_counter()
        try:
            await client.get(f"{BASE}/health")
            out.append((time.perf_counter() - t0) * 1000)
        except Exception:
            pass
        await asyncio.sleep(interval)


async def load_worker(client, path: str, stop: asyncio.Event, counter: list[int]) -> None:
    while not stop.is_set():
        try:
            await client.post(f"{BASE}{path}")
            counter[0] += 1
        except Exception:
            pass


async def run_condition(name: str, path: str | None, concurrency: int, seconds: float) -> dict:
    """path=None 이면 부하 없이 기준선만 측정한다."""
    import httpx

    limits = httpx.Limits(max_connections=concurrency + 8, max_keepalive_connections=concurrency + 8)
    async with httpx.AsyncClient(limits=limits, timeout=30.0) as client:
        await client.get(f"{BASE}/health")  # 커넥션 워밍업

        stop = asyncio.Event()
        health_ms: list[float] = []
        done = [0]

        tasks = [asyncio.create_task(probe_health(client, stop, health_ms, 0.02))]
        if path is not None:
            tasks += [asyncio.create_task(load_worker(client, path, stop, done)) for _ in range(concurrency)]

        t0 = time.perf_counter()
        await asyncio.sleep(seconds)
        stop.set()
        elapsed = time.perf_counter() - t0
        # 워커는 stop 을 보고 스스로 빠져나온다. 진행 중인 요청까지만 기다리고,
        # 그 안에 안 끝나면 취소한다 — 높은 동시성에서 백로그 때문에 teardown 이 막히는 것을 막는다.
        try:
            await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), timeout=10.0)
        except TimeoutError:
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    return {
        "condition": name,
        "load_path": path,
        "concurrency": concurrency if path else 0,
        "health_samples": len(health_ms),
        "health_p50_ms": round(statistics.median(health_ms), 2) if health_ms else None,
        "health_p95_ms": round(pct(health_ms, 0.95), 2) if health_ms else None,
        "health_p99_ms": round(pct(health_ms, 0.99), 2) if health_ms else None,
        "health_max_ms": round(max(health_ms), 2) if health_ms else None,
        "predict_rps": round(done[0] / elapsed, 1) if path else None,
    }


def diagnose() -> dict:
    """부하 측정 결과를 해석하는 데 필요한 보조 측정.

    ① 추론 1회 소요와 구간별 분해 — 이벤트 루프를 얼마나 오래 잡는지
    ② 스레드 병렬 속도향상 — 이 작업이 GIL 을 놓는지(놓지 않으면 스레드 오프로딩이 무의미)
    """
    import threading

    import pandas as pd

    from app.ml.predictor import RiskPredictor, load_model_bundle, normalize_features

    predictor = RiskPredictor()
    for _ in range(50):
        predictor.predict_sync(FEATURES)

    def timeit(fn, n: int = 300) -> float:
        for _ in range(30):
            fn()
        t0 = time.perf_counter()
        for _ in range(n):
            fn()
        return (time.perf_counter() - t0) / n * 1000

    path = predictor.minimal_artifact_path
    bundle = load_model_bundle(path)
    model = bundle["model"]
    columns = tuple(bundle["feature_columns"])
    snapshot = normalize_features(FEATURES, include_waist=False)
    frame = pd.DataFrame([{c: snapshot.get(c) for c in columns}], columns=columns)

    breakdown = {
        "load_model_bundle_ms": round(timeit(lambda: load_model_bundle(path)), 3),
        "normalize_features_ms": round(timeit(lambda: normalize_features(FEATURES, include_waist=False)), 3),
        "dataframe_build_ms": round(timeit(lambda: pd.DataFrame([{c: snapshot.get(c) for c in columns}], columns=columns)), 3),
        "predict_proba_ms": round(timeit(lambda: model.predict_proba(frame)), 3),
        "predict_sync_total_ms": round(timeit(lambda: predictor.predict_sync(FEATURES)), 3),
    }

    # GIL 판별: 4스레드로 같은 일을 나눠 해도 빨라지지 않으면 GIL-bound 다.
    n = 400
    t0 = time.perf_counter()
    for _ in range(n):
        predictor.predict_sync(FEATURES)
    serial = time.perf_counter() - t0

    def worker(count: int) -> None:
        for _ in range(count):
            predictor.predict_sync(FEATURES)

    threads = [threading.Thread(target=worker, args=(n // 4,)) for _ in range(4)]
    t0 = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    parallel = time.perf_counter() - t0

    return {
        "breakdown": breakdown,
        "gil_check": {
            "serial_ms": round(serial * 1000, 1),
            "threads4_ms": round(parallel * 1000, 1),
            "speedup": round(serial / parallel, 2),
            "interpretation": "1.0 부근 = GIL-bound (스레드 오프로딩으로 이벤트 루프가 풀리지 않음)",
        },
    }


async def main_async(concurrency: int, seconds: float) -> None:
    results = []
    for name, path in (
        ("A. 부하 없음(기준선)", None),
        ("B. 블로킹 추론", "/predict-blocking"),
        ("C. run_in_executor (현재 구현)", "/predict-executor"),
    ):
        print(f"  측정 중: {name} ...", flush=True)
        results.append(await run_condition(name, path, concurrency, seconds))
        await asyncio.sleep(1.0)  # 조건 간 잔여 부하 배출

    print("  진단 측정(추론 분해·GIL 판별) ...", flush=True)
    diag = diagnose()

    meta = {
        "cpu_count": os.cpu_count(),
        "python": sys.version.split()[0],
        "concurrency": concurrency,
        "seconds_per_condition": seconds,
        "note": "health 지연 = 예측 부하 중 가벼운 엔드포인트의 응답 시간(이벤트 루프 점유 지표)",
    }

    print()
    header = f"{'조건':<32}{'n':>6}{'health P50':>12}{'P95':>10}{'P99':>10}{'최대':>10}{'예측 RPS':>12}"
    print(header)
    print("-" * len(header))
    for r in results:
        print(
            f"{r['condition']:<32}"
            f"{r['health_samples']:>6d}"
            f"{r['health_p50_ms']:>10.2f}ms"
            f"{r['health_p95_ms']:>8.2f}ms"
            f"{r['health_p99_ms']:>8.2f}ms"
            f"{r['health_max_ms']:>8.2f}ms"
            f"{(r['predict_rps'] if r['predict_rps'] is not None else 0):>12.1f}"
        )

    print()
    b = diag["breakdown"]
    print(f"추론 1회 분해: predict_proba {b['predict_proba_ms']}ms + DataFrame {b['dataframe_build_ms']}ms "
          f"→ 합계 {b['predict_sync_total_ms']}ms (모델 로드는 lru_cache 로 {b['load_model_bundle_ms']}ms)")
    g = diag["gil_check"]
    print(f"GIL 판별: 직렬 {g['serial_ms']}ms vs 4스레드 {g['threads4_ms']}ms → 속도향상 {g['speedup']}x")

    out = REPO_ROOT / "scripts" / "bench" / "async_bench_result.json"
    out.write_text(
        json.dumps({"meta": meta, "results": results, "diagnostics": diag}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"\n결과 저장: {out.relative_to(REPO_ROOT)}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serve", action="store_true", help="내부용: 서버 프로세스로 기동")
    ap.add_argument("--concurrency", type=int, default=64)
    ap.add_argument("--seconds", type=float, default=6.0)
    args = ap.parse_args()

    if args.serve:
        serve()
        return

    server = subprocess.Popen(
        [sys.executable, __file__, "--serve"],
        cwd=str(REPO_ROOT),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        import httpx

        for _ in range(120):  # 모델 로드까지 기다린다
            try:
                if httpx.get(f"{BASE}/health", timeout=1.0).status_code == 200:
                    break
            except Exception:
                time.sleep(0.5)
        else:
            raise SystemExit("서버 기동 실패")

        print(f"서버 준비됨 (동시성 {args.concurrency}, 조건당 {args.seconds}s)\n")
        asyncio.run(main_async(args.concurrency, args.seconds))
    finally:
        server.terminate()
        server.wait(timeout=10)


if __name__ == "__main__":
    main()

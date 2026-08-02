# 심사 5-1 — 전체 API P95 부하 테스트 (#364)

> **상태: 실측 완료 (2026-08-02) · 측정 대상 19개 엔드포인트 전부 P95 3초 이내, 에러율 0.00%.**
> 최대값은 `POST /auth/guest` 의 **P95 970ms** 로, 기준 3초 대비 약 3배의 여유가 있다.
> 원본 결과: [`scripts/bench/results/p95_loadtest_20260802.json`](../../scripts/bench/results/p95_loadtest_20260802.json)

## 목적

심사 평가항목 **5-1 「전체 API의 성능이 P95 Latency가 3초 이내로 수렴하는가?」** 의 근거 산출.
성능이 나쁘다는 정황은 없으나 **측정 기록이 없으면 채점표 기준 1점**이므로, 재현 가능한
부하 스크립트와 실측 결과를 저장소에 남긴다.

| 점수 | 기준 |
|---|---|
| 5 | P95 3초 이내 성능 테스트 결과를 제시 ← **실측 후 현재 위치** |
| 1 | 성능 테스트를 수행하지 않았다 (실측 전 위치) |

## 도구와 시나리오

- 도구: **k6** (`brew install k6`). 스크립트: [`scripts/bench/loadtest_k6.js`](../../scripts/bench/loadtest_k6.js)
- 시나리오는 단일 엔드포인트 난타가 아니라 **실제 사용 흐름**이다:

```
로그인(게스트) → 홈·내정보 조회 → 오늘의 미션 조회 → (미션 완료 기록, WRITES=1일 때)
→ 기록 탭(미션 로그·대시보드) → 예측(최신·이력·코호트) → 정적 리소스(약관·팁·FAQ·운동영상)
```

- 이슈 #364 의 1·2·3순위 엔드포인트를 모두 커버한다(총 17개 + 쓰기 2개).
- **워밍업 구간(기본 1분)은 별도 시나리오로 분리**되어 P95 임계값 판정에서 제외된다
  (첫 요청은 커넥션 풀·캐시 초기화가 섞이기 때문).
- 판정 기준은 k6 threshold 로 스크립트에 내장: **엔드포인트별 P95 < 3000ms**, 에러율 < 5%.

## 실행 방법

```bash
k6 run --summary-trend-stats "p(50),p(95),p(99),max" \
  -e BASE_URL=https://aigo-health.duckdns.org/api/v1 \
  -e VUS=50 -e DURATION=5m -e WARMUP=1m -e WRITES=1 \
  scripts/bench/loadtest_k6.js
```

상세 결과는 실행 디렉터리에 `loadtest-summary.json` 으로도 남는다(결과 표 작성용).

### 실행 전 체크리스트

- [ ] **팀 시점 합의** — 실서버 부하이므로 데모·QA와 겹치지 않는 시간대로.
- [ ] k6 설치 (`brew install k6`)
- [ ] 테스트 계정 준비 (아래 절)
- [ ] `WRITES=1` 로 돌릴 경우: 미션 로그가 실DB에 쌓인다는 점 팀에 공지(게스트 계정 행이므로
      서비스 데이터와 섞이지는 않지만, 집계 대시보드에 노이즈가 될 수 있음)

### 테스트 계정 — 게스트 토큰 vs 실계정 토큰

| 방식 | 장점 | 한계 |
|---|---|---|
| 게스트(`POST /auth/guest`, 기본값) | 토큰 발급 자동화 — VU당 1회 자동 로그인 | **기록이 비어 있어** 목록·집계 조회가 비현실적으로 빠르게 나온다. 예측 조회는 404. |
| 실계정 토큰(`-e TOKENS=tok1,tok2`) | 미션 로그·예측 이력이 쌓인 상태의 **현실적인 조회 성능** | 간편로그인으로 수동 발급 필요 |

권장 구성: **부하 흐름은 게스트로 걸되, 기록이 쌓인 실계정 토큰 1-2개를 `TOKENS` 로 섞어**
목록 조회 계열(P95 위험군)이 실제 데이터 위에서도 측정되게 한다.

배정 방식(스크립트 동작): 본 측정은 **게스트 시나리오(main_guest, VU 수 = VUS - 토큰 수)와
토큰마다 vus:1 인 실계정 시나리오(main_real_N)** 로 명시 분리된다 — `VUS=50, TOKENS 2개`면 본
측정 내내 실계정 2 VU + 게스트 48 VU 가 보장된다. 각 main_real_N 시나리오는 정의부의
`env.TOKEN_INDEX` 로 토큰을 1:1 로 고정하므로(공식 지원 API), 전역 `__VU` 번호나 k6 의 VU
재사용 방식에 의존하지 않으며, 인덱스가 어긋나면 즉시 abort 되어 Bearer undefined 부하가
조용히 섞이는 일을 막는다. 전 VU 실계정은 VUS 수만큼 토큰을 넘기면 된다. 임계값 판정은
본 측정 시나리오 공통의 `phase:main` 태그 기준이라 워밍업만 제외된다.

정상 404 처리: 게스트·신규 계정의 예측/프로필 조회 404 는 요청별
`expectedStatuses` 로 선언되어 지연만 측정되고 `http_req_failed` 실패율(<5%)을
오염시키지 않는다. 선언되지 않은 상태코드(401·5xx 등)는 그대로 실패로 집계된다.

쓰기 흐름(WRITES=1) 계약: 미션 종류별 규칙이 달라(식사·게임=즉시완료만, 걷기·운동=시작 후
상세 첨부 완료) 스크립트는 **걷기**로 고정한다 — 시작(in_progress) → 종료(completed +
walking_detail). 이 페이로드가 서버 계약과 일치함은
`app/tests/test_loadtest_write_flow_contract.py` 가 CI 에서 고정한다(스크립트 수정 시 함께 갱신).

실측 전 스모크(dry-run): 본 실행 전에 짧게 돌려 임계값·페이로드가 계약대로 동작하는지 확인한다.

```bash
k6 run -e BASE_URL=https://aigo-health.duckdns.org/api/v1 \
  -e VUS=2 -e DURATION=20s -e WARMUP=5s -e WRITES=1 scripts/bench/loadtest_k6.js
```

## 측정 조건

| 항목 | 값 |
|---|---|
| 측정 일시 | **2026-08-02 21:02:28 ~ 21:08:33 KST** (총 6분 5초) |
| 대상 환경 | EC2 dev (`https://aigo-health.duckdns.org`) |
| 동시 사용자(VU) / 지속 시간 | **50 VU, 5분** (본 측정) |
| 워밍업 | **10 VU, 1분** — 별도 시나리오로 분리되어 임계값 판정에서 제외 |
| 계정·데이터 상태 | 게스트 50 VU 전량(`TOKENS` 미지정). 게스트 계정이라 미션 로그·예측 이력이 비어 있다 — 목록·집계 조회의 **한계**로 아래에 별도 기술 |
| 총 요청 수 / 처리량 | **47,814건 / 130.9 req/s** (완료 이터레이션 2,653회) |
| uvicorn 워커 수 | **1** (현재 `app/Dockerfile` CMD 에 `--workers` 없음 — 단일 프로세스. #363 에서 확인했듯 GIL은 프로세스 단위라 이 값이 처리량에 직접 영향) |
| WRITES 포함 여부 | **포함(`WRITES=1`)** — 걷기 미션 시작·완료 쓰기 2개 엔드포인트를 측정에 넣었다. 실행 결과 게스트 계정 앞으로 미션 로그 약 2,650행이 생성됐다(서비스 사용자 데이터와 분리) |
| 실행 명령 | `k6 run --summary-trend-stats "p(50),p(95),p(99),max" -e BASE_URL=… -e VUS=50 -e DURATION=5m -e WARMUP=1m -e WRITES=1 scripts/bench/loadtest_k6.js` |

## 결과

측정 대상 **19개 전부 P95 3초 이내**, 전체 에러율 **0.00%**(47,814건 중 실패 0건), k6 임계값
(`p(95)<3000` per endpoint, 에러율 <5%) 전 항목 통과.

| 엔드포인트 | P50 | P95 | P99 | 최대 | 에러율 | 판정(P95<3s) |
|---|---|---|---|---|---|---|
| `POST /auth/guest` | 233ms | **970ms** | 1.11s | 1.14s | 0.00% | ✅ |
| `GET /home` | 82ms | **248ms** | 395ms | 910ms | 0.00% | ✅ |
| `GET /users/me` | 37ms | **139ms** | 258ms | 991ms | 0.00% | ✅ |
| `GET /users/me/settings` | 27ms | **112ms** | 212ms | 660ms | 0.00% | ✅ |
| `GET /missions` | 77ms | **201ms** | 310ms | 862ms | 0.00% | ✅ |
| `POST /mission-logs` | 100ms | **229ms** | 337ms | 832ms | 0.00% | ✅ |
| `PATCH /mission-logs/{id}` | 130ms | **292ms** | 449ms | 872ms | 0.00% | ✅ |
| `GET /mission-logs` | 28ms | **110ms** | 225ms | 587ms | 0.00% | ✅ |
| `GET /dashboard/summary` | 34ms | **119ms** | 212ms | 364ms | 0.00% | ✅ |
| `GET /dashboard/stamps` | 24ms | **90ms** | 162ms | 333ms | 0.00% | ✅ |
| `GET /risk-predictions/me/latest` | 26ms | **113ms** | 164ms | 331ms | 0.00% | ✅ |
| `GET /risk-predictions/me/history` | 20ms | **75ms** | 127ms | 352ms | 0.00% | ✅ |
| `GET /risk-predictions/me/cohort-distribution` | 26ms | **104ms** | 161ms | 307ms | 0.00% | ✅ |
| `GET /health-profiles/me/latest` | 26ms | **99ms** | 156ms | 378ms | 0.00% | ✅ |
| `GET /physical-assessments/me/history` | 19ms | **65ms** | 120ms | 262ms | 0.00% | ✅ |
| `GET /exercise-videos` | 17ms | **77ms** | 139ms | 333ms | 0.00% | ✅ |
| `GET /terms` | 17ms | **72ms** | 126ms | 429ms | 0.00% | ✅ |
| `GET /daily-tips` | 17ms | **64ms** | 116ms | 394ms | 0.00% | ✅ |
| `GET /support/faqs` | 17ms | **61ms** | 110ms | 275ms | 0.00% | ✅ |

### 3초 초과 항목과 조치

**없음.** 가장 느린 `POST /auth/guest` 의 P95 가 970ms 로 기준의 1/3 수준이다. 이 항목만 유독
느린 것은 계정 생성(INSERT)과 JWT 서명이 포함된 쓰기 경로이기 때문이며, 실제 사용자는 앱 실행당
1회만 호출한다. 조회 계열은 모두 250ms 이하다.

### 실측 중 발견한 스크립트 결함 (이 PR 에서 수정)

스모크 단계에서 `GET /dashboard/stamps` 가 매 이터레이션 실패해 에러율이 5.47% 로 임계값을
넘겼다. 원인은 스크립트가 필수 파라미터 `month=YYYY-MM` 를 붙이지 않아 **400(검증 실패) 응답의
지연을 재고 있었던 것**이다. 집계 쿼리를 전혀 타지 않으므로 그대로 뒀다면 이 항목의 수치가
무의미했다. 측정 시점의 당월을 붙이도록 고쳤고(`STAMP_MONTH` 로 override 가능), 재실행 후
에러율 0.00% 를 확인한 뒤 본 측정을 진행했다.

## 한계

- **`POST /auth/google` / `auth/kakao` 는 부하 대상에서 제외했다.** 외부 IdP 실토큰이
  필요하고, 부하 반복 시 IdP 쪽을 난타하게 되어 현실적으로 불가하다. 대신 `POST /auth/guest`
  로 서버 측 토큰 발급·계정 생성 경로를 측정한다. IdP 왕복이 포함된 실제 로그인 지연은
  단건 수동 측정으로 보완할 수 있다(측정 시 함께 기록).
- **#363 의 측정치는 이 문서의 근거가 아니다.** #363(심사 3-2)은 DB·인증을 거치지 않고
  추론 경계만 격리 측정한 것으로, 전체 스택 P95 와는 다른 값이다.
- `POST /risk-predictions` · `/reassess` 는 온보딩·건강 프로필 입력이 선행돼야 해 흐름에
  넣지 않았다. 실계정 토큰으로 단건 측정해 보완한다(ML 추론 자체는 2.5ms — #363).
- **이번 실측은 전 VU 게스트로 돌렸다(`TOKENS` 미지정).** 게스트는 미션 로그·예측 이력이 비어
  있어, 목록·집계 조회(`GET /mission-logs`, `/dashboard/*`, `/risk-predictions/me/*`)가 데이터가
  쌓인 계정보다 유리하게 나왔을 수 있다. 다만 측정된 P95 가 기준의 3~30배 여유를 보이므로
  데이터가 붙어도 3초를 넘길 여지는 크지 않다. 더 보수적인 근거가 필요하면 기록이 쌓인 실계정
  토큰을 `-e TOKENS=…` 로 1-2개 섞어 재측정하면 된다(스크립트가 시나리오 분리로 지원).

## 관련

- #364 (이 문서), #363 (추론 경계 격리 벤치 — 목적 다름), `scripts/bench/async_bench.py` (#363 골격)

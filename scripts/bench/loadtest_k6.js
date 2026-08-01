// =====================================================================================
// 심사 5-1 「전체 API P95 Latency 3초 이내」 부하 테스트 스크립트 (#364)
//
// 실행 전 반드시 읽을 것: docs/perf/p95-load-test.md
//   - ⚠️ 실서버(EC2 dev)에 부하를 겁니다. 팀 시점 합의 없이 돌리지 마세요.
//   - 시나리오는 단일 엔드포인트 난타가 아니라 실제 사용 흐름:
//       로그인 → 홈 → 오늘의 미션 → (미션 완료 기록) → 기록 탭 → 예측 → 정적 리소스
//
// 실행 예 (워밍업 1분 제외 + 본측정 5분, 50 VU):
//   k6 run --summary-trend-stats "p(50),p(95),p(99),max" \
//     -e BASE_URL=https://aigo-health.duckdns.org/api/v1 \
//     -e VUS=50 -e DURATION=5m -e WARMUP=1m -e WRITES=1 \
//     scripts/bench/loadtest_k6.js
//
// 환경변수:
//   BASE_URL  대상 서버 (기본: EC2 dev)
//   VUS       동시 사용자 수 (기본 50)
//   DURATION  본측정 지속 시간 (기본 5m)
//   WARMUP    워밍업 시간 (기본 1m) — 커넥션 풀·캐시 초기화 구간. 임계값 판정에서 제외됨.
//   WRITES    1이면 미션 기록 쓰기(POST/PATCH mission-logs) 포함 (기본 0 — 실DB에 행이 쌓이므로
//             실측 시점에만 켠다)
//   TOKENS    쉼표로 구분한 실계정 액세스 토큰 목록 (선택). **앞번호 VU 에 1:1 배정**되고
//             나머지 VU 는 게스트 토큰을 발급받는다 — 실계정·게스트 혼합 부하(리뷰 반영:
//             '전부 실계정'이 아니라 토큰 수만큼만 실계정). 전 VU 실계정을 원하면 VUS 만큼 넘길 것.
//             미지정 시 전 VU 게스트.
//             ⚠️ 게스트 계정은 기록이 비어 있어 목록 조회가 비현실적으로 빠르게 나온다.
//                기록(미션 로그·예측 이력)이 쌓인 계정 토큰을 섞는 편이 정확하다.
// =====================================================================================
import http from "k6/http";
import { check, group, sleep } from "k6";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.2/index.js";

const BASE = __ENV.BASE_URL || "https://aigo-health.duckdns.org/api/v1";
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || "5m";
const WARMUP = __ENV.WARMUP || "1m";
const WRITES = __ENV.WRITES === "1";
const TOKENS = (__ENV.TOKENS || "").split(",").map((t) => t.trim()).filter(Boolean);

// 측정 대상 요청 이름 목록 — 이슈 #364 의 1·2·3순위를 흐름 순서로 커버.
// (auth/google·kakao 는 외부 IdP 실토큰이 필요해 부하 대상에서 제외 — 문서의 한계 절 참조.
//  대신 /auth/guest 로 서버 측 토큰 발급·계정 생성 경로를 측정한다.)
const NAMES = [
  "POST /auth/guest",
  "GET /home",
  "GET /users/me",
  "GET /users/me/settings",
  "GET /missions",
  "GET /mission-logs",
  "GET /dashboard/summary",
  "GET /dashboard/stamps",
  "GET /risk-predictions/me/latest",
  "GET /risk-predictions/me/history",
  "GET /risk-predictions/me/cohort-distribution",
  "GET /health-profiles/me/latest",
  "GET /physical-assessments/me/history",
  "GET /exercise-videos",
  "GET /terms",
  "GET /daily-tips",
  "GET /support/faqs",
];
if (WRITES) NAMES.push("POST /mission-logs", "PATCH /mission-logs/{id}");

// 엔드포인트별 P95 < 3초(심사 5-1) — 워밍업(scenario:warmup)은 판정에서 제외.
const thresholds = {
  "http_req_failed{scenario:main}": ["rate<0.05"],
};
for (const name of NAMES) {
  thresholds[`http_req_duration{scenario:main,name:${name}}`] = ["p(95)<3000"];
}

export const options = {
  scenarios: {
    warmup: {
      executor: "constant-vus",
      vus: Math.max(1, Math.floor(VUS / 5)),
      duration: WARMUP,
    },
    main: {
      executor: "constant-vus",
      vus: VUS,
      duration: DURATION,
      startTime: WARMUP,
    },
  },
  thresholds,
};

// VU마다 토큰 1개를 유지한다(모듈 스코프 = VU 스코프). 게스트 로그인은 VU당 1회만 —
// 매 이터레이션 새 게스트를 만들면 계정 생성만 난타하는 비현실적 부하가 된다.
let vuToken = null;

function authHeaders() {
  return { headers: { Authorization: `Bearer ${vuToken}`, "Content-Type": "application/json" } };
}

function ensureLogin() {
  if (vuToken) return;
  // 실계정 토큰은 앞번호 VU 에 1:1 배정 — 나머지는 게스트(혼합 부하, 리뷰 반영).
  if (TOKENS.length > 0 && __VU <= TOKENS.length) {
    vuToken = TOKENS[__VU - 1];
    return;
  }
  const r = http.post(`${BASE}/auth/guest`, null, { tags: { name: "POST /auth/guest" } });
  check(r, { "guest login 200": (res) => res.status === 200 });
  vuToken = r.json("access_token");
}

// 요청별 파라미터: tags + 인증 헤더 + **요청별 기대 상태코드**(responseCallback).
//   k6 의 http_req_failed 는 기본적으로 4xx 를 실패로 집계하므로, 게스트의 정상 404 를
//   허용하는 요청은 여기서 expectedStatuses 로 선언해야 전역 rate<0.05 임계값이 오염되지
//   않는다(리뷰 반영). 선언하지 않은 상태코드(401·500 등)는 그대로 실패로 잡힌다.
function params(name, okStatuses) {
  return Object.assign(
    { tags: { name }, responseCallback: http.expectedStatuses(...okStatuses) },
    authHeaders(),
  );
}

function get(path, name, okStatuses = [200]) {
  const r = http.get(`${BASE}${path}`, params(name, okStatuses));
  check(r, { [`${name} ok`]: (res) => okStatuses.includes(res.status) });
  return r;
}

export default function () {
  ensureLogin();

  group("홈 진입", () => {
    get("/home", "GET /home");
    get("/users/me", "GET /users/me");
    get("/users/me/settings", "GET /users/me/settings");
  });
  sleep(1);

  group("미션", () => {
    const missions = get("/missions?status=available", "GET /missions");
    if (WRITES) {
      // 실제 흐름의 '미션 완료 기록'. 실DB에 행이 남으므로 WRITES=1 일 때만.
      // 서버 계약(리뷰 반영): 종류별로 생성·완료 규칙이 다르다 —
      //   meal/game 은 즉시완료(completed)만 허용되어 in_progress 생성이 400 으로 거부되고,
      //   walking/exercise 완료(PATCH)는 각각 walking_detail / exercise_detail 이 필수다.
      // 측정 대상을 **걷기(walking)** 로 고정한다: 시작(in_progress) → 종료(completed +
      //   walking_detail). success 는 보내지 않는다 — 걷기 성공은 서버가 당일 누적으로 판정한다.
      // 이 페이로드 시퀀스는 app/tests/test_loadtest_write_flow_contract.py 가 서버 계약과
      //   일치함을 고정한다(스크립트를 바꾸면 그 테스트도 함께 갱신할 것).
      const walking = (missions.json("missions") || []).find((m) => m.mission_type === "walking");
      if (walking) {
        const createBody = JSON.stringify({
          mission_template_id: walking.mission_template_id,
          mission_type: "walking",
          status: "in_progress",
        });
        const created = http.post(`${BASE}/mission-logs`, createBody,
          params("POST /mission-logs", [200, 201]));
        check(created, { "mission-log create 2xx": (r) => r.status >= 200 && r.status < 300 });
        const logId = created.json("mission_log_id");
        if (logId) {
          const patchBody = JSON.stringify({
            status: "completed",
            walking_detail: { duration_min: 5, steps: 600 },
          });
          const patched = http.patch(`${BASE}/mission-logs/${logId}`, patchBody,
            params("PATCH /mission-logs/{id}", [200]));
          check(patched, { "mission-log patch 2xx": (r) => r.status >= 200 && r.status < 300 });
        }
      }
    }
  });
  sleep(1);

  group("기록 탭", () => {
    get("/mission-logs", "GET /mission-logs");
    get("/dashboard/summary", "GET /dashboard/summary");
    get("/dashboard/stamps", "GET /dashboard/stamps");
  });
  sleep(1);

  group("예측", () => {
    // 게스트·신규 계정은 예측 이력이 없어 404 가 정상 응답이다 — 지연만 측정하고 404 는 실패로 치지 않는다.
    get("/risk-predictions/me/latest", "GET /risk-predictions/me/latest", [200, 404]);
    get("/risk-predictions/me/history", "GET /risk-predictions/me/history", [200, 404]);
    get("/risk-predictions/me/cohort-distribution", "GET /risk-predictions/me/cohort-distribution", [200, 404]);
    get("/health-profiles/me/latest", "GET /health-profiles/me/latest", [200, 404]);
    get("/physical-assessments/me/history", "GET /physical-assessments/me/history", [200, 404]);
  });
  sleep(1);

  group("정적", () => {
    get("/exercise-videos", "GET /exercise-videos");
    get("/terms", "GET /terms");
    get("/daily-tips", "GET /daily-tips");
    get("/support/faqs", "GET /support/faqs");
  });
  sleep(1);
}

// 기본 터미널 요약 + 상세 JSON(문서의 결과 표 작성용, 실행 디렉터리에 생성).
export function handleSummary(data) {
  return {
    stdout: textSummary(data, { indent: " ", enableColors: true }),
    "loadtest-summary.json": JSON.stringify(data, null, 2),
  };
}

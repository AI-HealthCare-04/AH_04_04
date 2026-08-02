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
//   TOKENS    쉼표로 구분한 실계정 액세스 토큰 목록 (선택). 본 측정이 **게스트 시나리오
//             (main_guest, VU 수 = VUS - 토큰 수)와 토큰마다 vus:1 인 main_real_N 시나리오**로
//             명시 분리되어 혼합 비율이 실행 전체에서 보장된다(리뷰 반영). 각 main_real_N 은
//             시나리오 env.TOKEN_INDEX 로 토큰이 1:1 고정된다 — 전역 __VU 번호·존재하지 않는
//             VU 속성에 의존하지 않는다. 전 VU 실계정을 원하면 VUS 만큼 넘길 것. 미지정 시 전 VU 게스트.
//             ⚠️ 게스트 계정은 기록이 비어 있어 목록 조회가 비현실적으로 빠르게 나온다.
//                기록(미션 로그·예측 이력)이 쌓인 계정 토큰을 섞는 편이 정확하다.
// =====================================================================================
import http from "k6/http";
import exec from "k6/execution";
import { check, group, sleep } from "k6";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.2/index.js";

const BASE = __ENV.BASE_URL || "https://aigo-health.duckdns.org/api/v1";
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || "5m";
const WARMUP = __ENV.WARMUP || "1m";
const WRITES = __ENV.WRITES === "1";
const TOKENS = (__ENV.TOKENS || "").split(",").map((t) => t.trim()).filter(Boolean);
// GET /dashboard/stamps 의 필수 파라미터. 기본값은 측정 시점의 당월(앱이 기록 탭에서 보내는 값과 같다).
const STAMP_MONTH = __ENV.STAMP_MONTH || new Date().toISOString().slice(0, 7);

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

// 실계정/게스트 VU 수 — 본 측정을 **별도 시나리오로 명시 분리**해 혼합 비율을 보장한다(리뷰 반영).
//   __VU 는 테스트 전역 식별자라 시나리오 간 재사용·번호 범위를 보장할 수 없어, 전역 번호로
//   역할을 추론하면 본 측정이 전부 게스트가 되는 등 비율이 깨질 수 있다. 대신 시나리오별 VU 수를
//   고정하고, 실계정은 토큰마다 vus:1 시나리오의 env.TOKEN_INDEX 로 1:1 배정한다(아래).
const REAL_VUS = Math.min(TOKENS.length, VUS);
const GUEST_VUS = Math.max(0, VUS - REAL_VUS);

// 엔드포인트별 P95 < 3초(심사 5-1) — 워밍업(phase:warmup)은 판정에서 제외.
//   본 측정이 두 시나리오(main_guest·main_real)로 나뉘므로 시나리오명 대신 공통 phase 태그로 판정한다.
const thresholds = {
  "http_req_failed{phase:main}": ["rate<0.05"],
};
for (const name of NAMES) {
  thresholds[`http_req_duration{phase:main,name:${name}}`] = ["p(95)<3000"];
}

const scenarios = {
  warmup: {
    executor: "constant-vus",
    exec: "guestFlow",
    vus: Math.max(1, Math.floor(VUS / 5)),
    duration: WARMUP,
    tags: { phase: "warmup" },
  },
};
if (GUEST_VUS > 0) {
  scenarios.main_guest = {
    executor: "constant-vus",
    exec: "guestFlow",
    vus: GUEST_VUS,
    duration: DURATION,
    startTime: WARMUP,
    tags: { phase: "main" },
  };
}
// 실계정은 **토큰마다 vus:1 시나리오**를 만들고 시나리오 env 로 토큰 인덱스를 전달한다(리뷰 반영).
//   k6 의 exec.vu 에는 idInScenario 가 없어(idInTest·idInInstance 뿐) VU 번호 기반 배정은 신뢰할 수
//   없다 — 시나리오 env 는 공식 지원 API 라 1:1 배정이 구조적으로 고정된다. 문서 권장 토큰 수가
//   1-2개라 시나리오 수 부담도 없다.
for (let i = 0; i < REAL_VUS; i++) {
  scenarios[`main_real_${i + 1}`] = {
    executor: "constant-vus",
    exec: "realFlow",
    vus: 1,
    duration: DURATION,
    startTime: WARMUP,
    tags: { phase: "main" },
    env: { TOKEN_INDEX: String(i) },
  };
}

export const options = { scenarios, thresholds };

// VU마다 토큰 1개를 유지한다(모듈 스코프 = VU 스코프). 게스트 로그인은 VU당 1회만 —
// 매 이터레이션 새 게스트를 만들면 계정 생성만 난타하는 비현실적 부하가 된다.
// (한 VU 가 warmup 후 main 에 재사용되어도 게스트 토큰 재사용은 무해하다. 실계정 시나리오는
//  시나리오 env 의 TOKEN_INDEX 로 매 이터레이션 결정적으로 배정하므로 재사용과 무관하다.)
let vuToken = null;

function authHeaders() {
  return { headers: { Authorization: `Bearer ${vuToken}`, "Content-Type": "application/json" } };
}

function ensureGuestLogin() {
  if (vuToken) return;
  const r = http.post(`${BASE}/auth/guest`, null, { tags: { name: "POST /auth/guest" } });
  check(r, { "guest login 200": (res) => res.status === 200 });
  vuToken = r.json("access_token");
}

function ensureRealLogin() {
  // main_real_N 시나리오 전용 — 시나리오 정의의 env.TOKEN_INDEX 가 토큰을 1:1 로 고정한다.
  //   (k6 는 시나리오 env 를 해당 시나리오의 __ENV 에 주입한다 — 공식 지원 API)
  const token = TOKENS[Number(__ENV.TOKEN_INDEX)];
  if (!token) {
    // 배정 회귀 방어: 잘못된 인덱스로 Bearer undefined 가 나가면 측정 전체가 조용히 게스트도
    // 실계정도 아닌 401 부하가 된다 — 즉시 테스트를 중단해 원인을 드러낸다.
    exec.test.abort(`TOKEN_INDEX=${__ENV.TOKEN_INDEX} 에 해당하는 토큰이 없습니다`);
  }
  vuToken = token;
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

// 시나리오 진입점 — 로그인 방식만 다르고 사용자 흐름(journey)은 동일하다.
export function guestFlow() {
  ensureGuestLogin();
  journey();
}

export function realFlow() {
  ensureRealLogin();
  journey();
}

function journey() {
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
    // month(YYYY-MM)는 필수 — 누락하면 400 이라 집계 쿼리가 아니라 검증 실패 경로를 재게 된다(#364 실측에서 발견).
    get(`/dashboard/stamps?month=${STAMP_MONTH}`, "GET /dashboard/stamps");
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

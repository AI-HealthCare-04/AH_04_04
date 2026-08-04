# STS 안전망 카드 노출 지표 정의 (#373 → #429 개정)

`sts_overlay_events` 관련 지표·중복·보존 정책의 **단일 원천**이다. #242 DB 점검에서 분리된
후속(#373)의 결정 사항을 기록한다. 관련 코드: `scripts/sts_overlay_report.py`(집계),
`scripts/prune_sts_events.py`(보존 정리), `app/apis/v1/analytics_routers.py`(수집).

> **개정(#429, 2026-08-05)**: 발화율(노출/조회) 지표는 폐기했다. 분모 이벤트
> (`POST /events/sts-score-viewed`, `sts_score_view_events`)가 앱에 배선되지 않아 산출된 적이
> 없었고, 명세서 v1.3 정합 정리에서 라우트·테이블(마이그레이션 0023)과 함께 제거했다.
> 이후 관측 지표는 **주간 노출 사용자 수(절대량)** 와 노출 구성 분해다.

## 1. 지표 정의

**주간 노출 사용자 수 = 해당 주에 카드가 노출된 사용자 수**

| 항목 | 정의 |
|---|---|
| 노출 사용자 | 해당 주에 `sts_overlay_events` 가 1건 이상인 사용자 수 (`COUNT(DISTINCT user_id)`) |
| 집계 단위 | **사용자 · ISO 주** — `YEARWEEK(created_at, 3)`(월요일 시작), DB 저장 시각(운영 세션 tz = KST) 기준 |

- 노출 횟수가 아니라 **노출된 사용자 수**를 본다: 같은 사용자에게 카드가 여러 번 떠도
  발화 대상 집단의 크기는 변하지 않는다 — 컷(12초) 조정 판단에 필요한 것은 "얼마나 많은
  사용자가 걸리는가"다.

## 2. 중복 규칙 — 중복 허용 + `COUNT(DISTINCT user_id)`

앱은 fire-and-forget 전송이라 재시도로 같은 실제 노출이 여러 행 저장될 수 있다.
**멱등키를 두지 않는 것이 결정 사항이다**:

- 지표가 사용자 단위 DISTINCT 라 행 중복은 발화율·노출 사용자 수에 영향을 주지 않는다.
- 멱등키(노출 세션 UUID 등)는 앱-서버 계약 추가 대비 이득이 없다 — 행 수를 세는 지표를
  만들 때 재검토한다.
- 행 수를 쓰는 곳(노출 구성 breakdown)은 `events`(행)와 `users`(사용자)를 분리 표기해
  중복이 사용자 수로 오독되지 않게 한다.
- **평균·분포도 사용자 단위가 기본이다**: breakdown 의 `avg_sts_sec`/`avg_bmi` 는 사용자별로
  먼저 접은 뒤(사용자 내 평균) 사용자 간 균등 가중으로 낸다 — 원시 행 평균은 재시도가 많은
  사용자를 과대표집해 컷 재조정 판단을 편향시킨다.

## 3. 보존 — 90일, 운영자 월 1회 수동 정리

- **보존기간 90일**: 발화율 관측·컷 재조정 근거는 최근 12주 추이면 충분하다. 개인 단위
  원시 이벤트를 그 이상 보관하지 않는다(최소 보관).
- **삭제 주체**: 운영자가 월 1회 `uv run --no-sync python -m scripts.prune_sts_events --apply`
  실행(기본은 dry-run). 두 이벤트 테이블 모두 대상.
- **탈퇴 사용자**: 보존기간과 무관하게 탈퇴 시점에 FK 그래프 파기(#356)로 즉시 삭제된다
  — `user_reachable_conditions` 가 FK 로 자동 포함하며 회귀 테스트(`test_withdrawal.py`)가
  새 테이블도 자동 커버한다.

## 4. 저장 필드와 사유 (최소 저장 재판정)

| 테이블 | 필드 | 판정 | 사유 |
|---|---|---|---|
| `sts_overlay_events` | `tier` | 유지 | 카드 변형(basic/strong) 구분 — 발화율 세분화 |
| | `sts_sec` | 유지 | 발화 컷(5STS ≥ **12초**)의 노출 시점 입력값 스냅샷 — 컷 재조정 분석의 직접 근거 |
| | `bmi` | 유지 | strong 티어 컷(BMI ≥ **25**)의 노출 시점 입력값 스냅샷 — 티어 경계 재조정 근거 |
| | `score_band` | 유지 | 발화 조건(구간 ≠ caution)의 입력값 — 구간별 발화 구성 확인 |
| `sts_score_view_events` | — | **제거(#429)** | 분모 폐기와 함께 테이블 자체를 마이그레이션 0023 에서 drop |

- 세 필드 모두 **발화 조건의 입력값**이다(§3.4: `sts >= 12 AND band != caution`, 티어는
  `bmi >= 25`). 프로필·측정 테이블의 현재값과 조인하면 노출 이후 변경이 섞여 "그때 왜
  떴는지"를 재구성할 수 없으므로 노출 시점 스냅샷이 필요하다.
- 원시값 보관의 균형추가 §3 의 보존 90일이다.

## 5. 집계 쿼리와 인덱스 검증

집계·정리 쿼리는 `WHERE created_at 범위` + `user_id` 만 읽는다. `sts_overlay_events` 에
**`(created_at, user_id)` 복합 인덱스**(`ix_*_created_user`, 마이그레이션 0019)를 두어
커버링 인덱스로 돌게 했다. 기존 `user_id` 단일 인덱스는 FK·탈퇴 파기(user_id 등가 조건)용으로 유지.

로컬 MySQL 8 에 마이그레이션 적용 후(사용자 200 · 노출 5,000행) EXPLAIN 확인:

```
EXPLAIN SELECT YEARWEEK(created_at, 3) AS iso_week, COUNT(DISTINCT user_id) AS exposed_users
        FROM sts_overlay_events
        WHERE created_at >= DATE_SUB(NOW(), INTERVAL 12 WEEK)
        GROUP BY iso_week ORDER BY iso_week;
-- type: range | key: ix_sts_overlay_events_created_user | Extra: Using where; Using index; Using filesort

EXPLAIN SELECT COUNT(*) FROM sts_overlay_events
        WHERE created_at < DATE_SUB(NOW(), INTERVAL 90 DAY);   -- prune 대상 산정
-- type: range | key: ix_sts_overlay_events_created_user | Extra: Using where; Using index
```

- `Using index` = 테이블 접근 없는 커버링 스캔. `Using filesort` 는 `YEARWEEK` 파생값
  GROUP BY 에 대한 것으로, 대상이 주 단위로 접힌 소수 행이라 문제되지 않는다.
- 노출 구성 breakdown(`EXPOSURE_BREAKDOWN`)은 사용자 단위로 접는 내부 서브쿼리가
  sts_sec·bmi 를 읽어 커버링이 아니지만, 대상이 보존 90일로 상한된 원시 행이라 허용한다.

## 6. 알려진 한계

- **시연·QA 오염**: `prediction_feedbacks.is_test` 같은 마킹이 없다 — 노출 지표는 컷 조정의
  방향 판단용 내부 지표라 허용했다. 심사 자료에 수치를 인용하게 되면 그때 마킹을 추가한다.
- **주 경계**: 저장 시각(KST 세션) 기준이라 주 경계 부근 이벤트는 사용자 체감 주와 1일
  이내로 어긋날 수 있다 — 주 단위 추이 판단에는 영향 없다.

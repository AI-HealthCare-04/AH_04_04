# PR #39 Health/Risk v7.3 정합 및 재평가 Troubleshooting

## 🧩 문제 상황 (What Happened)

---

- API 명세 v7.3을 구현하면서 health profile, physical assessment, risk reassessment 계약과 기존 dev 코드 사이에 여러 불일치가 확인됨
- 특히 마이그레이션과 재평가 흐름은 단위 테스트만 통과해도 실제 MySQL 또는 사용자 흐름에서 잘못된 데이터를 만들 수 있는 상태였음

| 항목 | 발견된 문제 | 영향 |
| --- | --- | --- |
| Alembic revision | `0004_add_activity_level_change_logs`가 32자를 초과 | MySQL `alembic_version.version_num VARCHAR(32)`에 저장 실패 가능 |
| 0004 downgrade | FK가 사용하는 인덱스를 먼저 삭제 | MySQL error 1553으로 downgrade 실패 |
| migration 검증 | 왕복/drift 검사가 CI에 없음 | 같은 유형의 마이그레이션 결함 재발 가능 |
| 체력평가 | 걷기 측정 스킵 시 기존 NORMAL/HARD 레벨을 EASY로 덮어씀 | 재검사 사용자의 난이도 조용한 강등 |
| 재평가 latest | SERVICE_LOG 스냅샷이 최신 profile로 다시 선택됨 | 자기보고 profile 대신 합성 snapshot을 GET/다음 재평가에서 사용 |
| 레벨 변경 이력 | 자동 레벨 변경을 최초 검사/사용자 수락으로 기록 | 감사 데이터 의미 불일치 |
| risk reassess | `SERVICE_LOG` 라벨만 저장하고 실제 활동 로그는 미사용 | 7일/14일 재평가가 동일 입력으로 예측되고 데이터 출처가 부정확 |

- 이 상태에서 머지하면 DB migration 실패, 잘못된 활동 난이도, 사실과 다른 재평가 이력이 함께 남을 수 있었음

## ⚙️ 환경 정보 (Environment)

---

- **Project**: Chronical
- **PR**: [#39 fix: align v7.3 health/risk APIs and migration gate](https://github.com/AI-HealthCare-04/AH_04_04/pull/39)
- **Base Branch**: `dev`
- **Backend Scope**: health profile / physical assessment / risk prediction / Alembic migration
- **Database**: MySQL + Alembic
- **관련 API**:
  - `POST /health-profiles`
  - `POST /physical-assessments`
  - `POST /risk-predictions/reassess`
- **관련 모델 피처**: `pa_walk_30min_5days`, `pa_muscle_2days`

## 💡 시도한 해결 방법 (Tried Solutions)

---

- 명세 v7.3과 기존 DTO/서비스/응답 계약을 대조
  - health profile 생성 요청 필드와 physical assessment 응답 형식을 명세에 맞춤
  - reassess 응답에서 더 이상 공개하지 않는 `model_variant`를 제거

- migration을 로컬 MySQL에서 실제로 왕복 실행
  - `upgrade head -> downgrade base -> upgrade head -> alembic check` 순서로 검증
  - revision ID 길이와 FK 인덱스 삭제 순서 문제를 발견

- 걷기 스킵, 재평가 snapshot, 자동 레벨 변경을 fake repository 기반 회귀 테스트로 재현
  - 단순 응답 확인이 아니라 기존 profile 값과 audit log가 어떻게 변하는지 검증

- 최초에는 reassess가 source profile의 `walking_practice`와 `strength_exercise`를 복사하고, `SERVICE_LOG`/window만 저장
  - 이 방식은 구현 범위를 작게 유지하지만 실제 로그 기반 재평가라는 계약과 맞지 않음을 재리뷰에서 확인

- 활동 로그에서 임의의 새로운 임계값을 만들지 않고, 기존 모델 피처 이름을 기준으로 재평가 규칙을 확정
  - 걷기: 하루 30분 이상인 날 5일
  - 근력: 활동 기록일 2일
  - 14일 창은 같은 주간 기준을 2배 적용

## ✅ 최종 해결 방법 (Solution)

---

### 1. 마이그레이션을 실행 가능한 상태로 수정하고 CI 게이트화

| 항목 | 최종 처리 | 재발 방지 |
| --- | --- | --- |
| revision ID | 32자 이하인 `0004_add_activity_change_logs`로 변경 | MySQL 버전 테이블 제약 충족 |
| downgrade | FK 인덱스 단독 삭제를 제거하고 table drop에 맡김 | MySQL 1553 방지 |
| migration 검증 | 왕복 + `alembic check` 스크립트를 CI에 연결 | 모든 PR에서 실제 DB 검증 |

```text
upgrade head
-> downgrade base
-> upgrade head
-> alembic check
```

### 2. 체력평가와 활동 난이도 이력의 의미를 정리

- 걷기 속도를 산정할 수 없는 경우에는 기존 활동 레벨을 유지
  - profile이 없을 때만 기본 `easy/rule` profile 생성
- 최초 체력평가 생성은 `level_reason=initial_test` 유지
- 재측정으로 바뀐 레벨은 `level_reason=rule`로 기록
- 자동 규칙 변경은 사용자의 명시적 수락이 아니므로 `accepted_by_user=false`로 기록
- 실제 레벨이 바뀐 경우에만 `activity_level_change_logs`를 생성

### 3. 재평가 snapshot의 조회 경로를 분리

- SERVICE_LOG 재평가 profile은 이력 보존용 snapshot으로 생성
- `get_latest_profile`에서 `activity_input_source != SERVICE_LOG` 조건을 사용
- 따라서 일반 profile 조회와 다음 reassess의 source는 가장 최근 자기보고 profile을 계속 사용

### 4. 재평가를 실제 서비스 로그 기반으로 구현

`POST /risk-predictions/reassess`는 KST 기준 최근 7일 또는 14일의 `PhysicalActivityLog`를 읽어 모델 입력을 다시 만든다.

| 모델 피처 | 로그 기반 판정 | 7일 기준 | 14일 기준 |
| --- | --- | --- | --- |
| `pa_walk_30min_5days` | WALKING 로그의 일별 `duration_min` 합산 | 30분 이상 5일 | 30분 이상 10일 |
| `pa_muscle_2days` | CHAIR_STAND / SEATED_EXERCISE / STANDING_EXERCISE 기록일 | 2일 | 4일 |

- 걷기와 근력은 서로 다른 `ActivityType`으로 분리 집계
- 재평가 snapshot은 실제 파생값이므로 다음과 같이 기록

```text
activity_input_source = service_log
input_method          = service_log
has_estimated_value   = true
```

- `activity_window_days`는 7 또는 14만 허용한다. API DTO뿐 아니라 내부 집계 헬퍼도 동일하게 검증한다.

## 🧪 검증 및 재발 방지

---

- migration: MySQL 왕복 + drift check를 CI에서 실행
- physical assessment:
  - 걷기 스킵 시 기존 레벨 유지
  - 최초 검사와 재측정의 `level_reason` 구분
  - 자동 변경의 `accepted_by_user=false`
- reassess:
  - SERVICE_LOG snapshot이 latest source로 재사용되지 않음
  - 7일/14일 활동 창의 경계값
  - 같은 날 걷기 시간 합산
  - 걷기와 근력의 분리 집계
  - 지원하지 않는 활동 창 거부
- 최종 검증: `pytest 118 passed, 3 skipped`, ruff, mypy, GitHub Actions CI 통과

## 📚 참고 자료 (References)

---

- [PR #39](https://github.com/AI-HealthCare-04/AH_04_04/pull/39)
- `scripts/check_migrations.py`
- `app/services/activity_metrics.py`
- `app/services/risk_prediction.py`
- `app/services/physical_assessment.py`
- `app/repositories/health_profile_repository.py`
- 모델 피처 정의: `app/ml/predictor.py`

# Mission / Activity / Sensor API 사용 예시 (Android 담당자용)

API 명세서 v7.1 기준. 모든 요청은 아래 헤더 필요:

```
Content-Type: application/json
Authorization: Bearer <access_token>
```

- 모든 JSON 필드는 **snake_case**
- 성공/실패는 HTTP 상태코드로 구분, 에러 본문은 항상 `{"error_detail": "..."}`
- 온보딩/로그인 완료 후 발급받은 access token 사용

---

## 0. 미션 목록 조회 — `GET /api/v1/missions`

쿼리(모두 optional): `status=available`, `mission_type=walking|exercise|meal|game`, `level=easy|normal|hard`

**응답 200**
```json
{
  "missions": [
    {
      "mission_template_id": 1,
      "mission_type": "walking",
      "title": "동네 한 바퀴 걷기",
      "description": "천천히 동네를 한 바퀴 걸어보세요...",
      "level": "normal",
      "target_value": 1000,
      "target_unit": "steps",
      "requires_safety_notice": false,
      "daily_count_limit": null,
      "reward_points": 10
    }
  ]
}
```

---

## 1. 걷기 미션 시작 — `POST /api/v1/mission-logs`

걷기/운동은 **먼저 시작(in_progress)** 하고, 끝나면 PATCH로 완료한다.

**요청**
```json
{
  "mission_template_id": 1,
  "mission_type": "walking",
  "status": "in_progress"
}
```
> 운동(exercise)이 `requires_safety_notice=true`면 시작 시 아래를 함께 보내야 함(안 보내면 400):
> ```json
> { "mission_template_id": 2, "mission_type": "exercise", "status": "in_progress",
>   "safety_notice_confirmed": true, "safety_notice_confirmed_at": "2026-07-08T09:00:00" }
> ```

**응답 201**
```json
{
  "mission_log_id": 10,
  "status": "in_progress",
  "success": false,
  "counted_for_daily": false,
  "daily_limit_reached": false,
  "earned_points": 0,
  "daily_result": "none"
}
```
→ 여기서 받은 `mission_log_id`를 센서 저장과 완료(PATCH)에 사용한다.

---

## 2. 센서 세션 저장 — `POST /api/v1/sensor-sessions`

만보기/가속도계 측정 요약을 mission_log에 붙여 저장. (걷기 완료 PATCH 전에 보내도 됨)

**요청**
```json
{
  "mission_log_id": 10,
  "sensor_type": "step_counter",
  "detected_count": 1100,
  "duration_sec": 720,
  "motion_score": 0.87,
  "recognition_status": "success",
  "raw_summary": { "avg_cadence": 95 }
}
```
- `sensor_type`: `accelerometer` | `gyroscope` | `step_counter`
- `recognition_status`: `success` | `low_confidence` | `failed` | `manual_override`

**응답 201**
```json
{ "sensor_session_id": 1, "recognition_status": "success" }
```

---

## 3. 걷기 완료 — `PATCH /api/v1/mission-logs/{mission_log_id}`

**요청** (`walking_detail` 필수)
```json
{
  "status": "completed",
  "success": true,
  "walking_detail": { "duration_min": 12.0, "distance_km": 0.8, "steps": 1100 }
}
```

**응답 200**
```json
{
  "mission_log_id": 10,
  "status": "completed",
  "daily_total_min": 22.0,
  "success": true,
  "counted_for_daily": true,
  "daily_result": "success",
  "sync_status": "synced"
}
```
> `daily_total_min`은 **같은 날 걷기 시간 서버 합산값**(이어서 개념 없음).

---

## 4. 운동 완료 — `PATCH /api/v1/mission-logs/{mission_log_id}`

**요청** (`exercise_detail` 필수)
```json
{
  "status": "completed",
  "success": true,
  "perceived_difficulty": "just_right",
  "pain_reported": false,
  "dizziness_reported": false,
  "exercise_detail": { "reps": 10, "sets": 1 }
}
```
**응답 200**: 위와 동일 구조 (`daily_total_min`은 걷기 전용이라 운동은 `null`)

---

## 5. 식사 즉시완료 — `POST /api/v1/mission-logs`

식사/게임은 **시작 없이 바로 completed**.

**요청**
```json
{
  "mission_template_id": 4,
  "mission_type": "meal",
  "status": "completed",
  "success": true,
  "input_method": "manual",
  "meal_detail": { "protein_foods": ["egg", "tofu"], "protein_meal_count": 1, "raw_text": "계란이랑 두부 먹었어" }
}
```
**응답 201**
```json
{ "mission_log_id": 11, "status": "completed", "success": true,
  "counted_for_daily": true, "daily_limit_reached": false, "earned_points": 5, "daily_result": "success" }
```
> 식사는 **1일 1회만 카운트**. 두 번째부터는 `daily_limit_reached: true`, `counted_for_daily: false`(저장은 됨, 팝업 근거).

---

## 6. 게임 즉시완료 — `POST /api/v1/mission-logs`

**요청**
```json
{
  "mission_template_id": 5,
  "mission_type": "game",
  "status": "completed",
  "success": true,
  "game_detail": { "game_type": "card_match", "score": 80, "success_count": 8, "mistake_count": 2, "completed": true }
}
```
**응답 201**: 식사와 동일 구조.

---

## 흔한 에러

| 상태 | 상황 | error_detail 예시 |
|---|---|---|
| **400** | 안전 고지 미확인으로 운동/걷기 시작 | `안전 고지 확인이 필요합니다.` |
| **400** | request `mission_type`이 템플릿과 불일치 | `미션 종류가 템플릿과 일치하지 않습니다.` |
| **400** | 식사/게임을 in_progress로 생성 | `식사/게임 미션은 즉시 완료로만 생성할 수 있습니다.` |
| **400** | 운동/걷기를 completed로 바로 생성 | `운동/걷기 미션은 시작(in_progress)으로만 생성할 수 있습니다.` |
| **400** | 걷기 완료인데 `walking_detail` 없음 | `걷기 완료에는 walking_detail이 필요합니다.` |
| **400** | 걷기 미션에 `exercise_detail` 전송 | `걷기 미션에는 exercise_detail을 보낼 수 없습니다.` |
| **400** | 센서 mission_log_id가 내 것이 아님/없음 | `센서 데이터가 올바르지 않습니다.` |
| **401** | 토큰 없음/만료 | `인증이 필요합니다.` |
| **404** | 존재하지 않는 mission_log PATCH | `미션 로그를 찾을 수 없습니다.` |
| **409** | 이미 완료된 미션을 다시 PATCH | `이미 완료된 미션입니다.` |
| **422** | enum/필수값 형식 오류 (Pydantic) | (FastAPI 기본 검증 형식) |

> ⚠️ 422(입력 검증)는 현재 `{"error_detail"}`이 아닌 FastAPI 기본 포맷으로 나갑니다. (공통 핸들러 통일은 별도 이슈)

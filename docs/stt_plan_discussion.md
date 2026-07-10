# STT(음성 건강체크) 구현 계획 — 팀 논의용

> 대상 API: `POST /api/v1/health-check/sessions/{session_id}/voice` (명세 v7.3 R19)
> 작성 배경: 앱 화면 작업을 이어가며 STT 기능을 시작하려는데, **명세와 실제 백엔드 구현이 달라** 방향을 먼저 정해야 함.

---

## 1. 전제 (이미 정해진 것)

- **STT(음성 → 텍스트) 변환은 앱(온디바이스)에서 한다.** Android `SpeechRecognizer`(ko-KR) 사용.
- **오디오 파일은 서버로 보내지 않는다.** 서버는 변환된 **텍스트(`raw_transcript`)만** 받는다.
  - → 프라이버시 안전, 서버 STT 비용 0, 네트워크 부담 최소.
- 음성 입력은 **건강체크(온보딩) 프로필 입력의 대안 수단**이다. 폼 입력과 최종 저장 지점(`POST /health-profiles`)은 동일.

## 2. ⚠️ 핵심 이슈 — 명세 R19 ≠ 실제 백엔드

`/voice` 엔드포인트는 **이미 존재하지만**, 하는 일이 명세와 다르다.

| 항목 | v7.3 명세 R19 | 현재 백엔드 (`save_voice_transcript`) |
|---|---|---|
| 응답 | `{ recognized: {field, value}, confirm_prompt, needs_confirmation }` | `{ session_id, status, input_method, raw_transcript, ... }` |
| 동작 | 텍스트를 **파싱해 필드/값 추출** + 확인 문구 생성 | 텍스트를 **그대로 저장**하고 세션을 `completed`로 종료 |
| 다중 필드 | 필드마다 호출 → 확인 (키 → 몸무게 → 허리 …) | 첫 호출에 세션이 끝나 **다중 필드 불가** |

즉, 명세가 그리는 "서버가 알아듣고 되물어보는" 흐름이 **아직 구현되지 않았고**, 현재 코드는 사실상 "받아쓴 텍스트 로그"만 남긴다.
STT를 제대로 하려면 **"음성 텍스트에서 `field=value`를 누가 파싱하느냐"**를 먼저 합의해야 한다.

> 참고: v7.3 명세서의 "완료 상태 = 대기중"은 전부 리셋된 값이라 신뢰하지 말 것. 실제로는 대부분 구현·머지 완료 상태.

## 3. 결정할 것 — 파싱을 어디서 하나 (3안 비교)

예시 입력: `"내 키는 160센티미터야"` → 뽑아야 할 결과: `height_cm = 160.0`
수집 대상 필드: `height_cm, weight_kg, waist_cm, birth_date, sex, walking_practice(bool), strength_exercise(bool), kidney_status, protein_restriction_status`

| | A. 백엔드 규칙기반(regex) | B. 백엔드 LLM(Claude) | C. 앱에서 파싱 |
|---|---|---|---|
| **동작** | 서버가 정규식/한국어 숫자 파싱 → field/value + 확인문구 반환 | 서버가 Claude로 추출 + 자연스러운 확인문구 생성 | 앱이 Kotlin에서 파싱 → 구조화해 `health-profiles`로 바로 전송 |
| **명세 R19 정합** | ✅ 정확히 일치 | ✅ 일치(+품질↑) | ❌ `/voice`는 로그용으로만 남음 |
| **자연발화 강건성** | 🔸 약함 ("백육십", "일미터육십" 등 취약) | ✅ 강함 (노인 자연발화에 유리) | 🔸 약함 (A와 동일 한계) |
| **작업량** | 백엔드 중(中): 파싱 로직 + 다중턴 세션 + confirm 루프 | 백엔드 중(中)~소: 프롬프트 + 호출 래핑 | 앱 중(中): Kotlin 파서 / 백엔드 변경 거의 없음 |
| **업데이트 용이성** | ✅ 서버 배포만으로 개선 | ✅ 프롬프트만 고치면 개선 | ❌ 앱 재배포 필요 |
| **비용/의존성** | 없음 | LLM API 비용·지연·외부 의존성 | 없음(오프라인 가능) |
| **주 담당** | 백엔드 | 백엔드 | 앱 |

### 추천: **A(백엔드 규칙기반)로 MVP 시작 → 인식 품질 부족하면 B로 승급**
근거:
- 명세 R19가 "필드당 한 문장 + 되묻기 확인 루프"를 전제하므로 발화가 `"내 키는 160센티미터야"`처럼 **제약된 형태** → regex로 충분히 커버 가능.
- 파싱이 서버에 있으면 앱 재배포 없이 개선 가능(노인 대상은 실사용 후 튜닝이 많이 필요).
- LLM(B)은 데모 완성도·강건성이 확실히 높지만 비용·지연·의존성이 붙음 → 프로젝트 lean 기조 고려 시 **품질이 실제로 부족할 때** 도입하는 게 합리적. A→B는 응답 계약이 동일해 앱 수정 없이 서버만 교체 가능.
- C(앱 파싱)는 오프라인 이점이 있으나 파싱 로직이 Kotlin에 갇혀 개선마다 재배포가 필요 → 튜닝이 잦은 STT엔 불리.

## 4. 방향 정해지면 각자 할 일

**A/B (백엔드 파싱) 선택 시**
- 백엔드:
  - `/voice` 응답 계약을 R19로 변경: `{ recognized:{field,value}, confirm_prompt, needs_confirmation }`
  - 세션을 **첫 발화에 종료하지 않도록** 수정(다중 필드 수집 지원). 확인(confirm) 시에만 값 확정.
  - 파싱기(A: regex+한국어 숫자 / B: Claude 호출) 구현.
- 앱:
  - `RECORD_AUDIO` 권한 + `SpeechRecognizer(ko-KR)`
  - "말하기 → 서버 확인문구 표시 → 예/아니오 → 다음 필드" 루프 UI
  - 모든 필드 수집 후 `POST /health-profiles (input_method="voice")`

**C (앱 파싱) 선택 시**
- 백엔드: `/voice`는 현재대로 로그 저장만(또는 제거). 변경 거의 없음.
- 앱: STT + Kotlin 파서 + 확인 UI + `health-profiles` 직접 전송.

## 5. 앱 화면 빌드 순서 (참고 — 백엔드 API는 전부 완성)

STT가 얹힐 "그릇"인 온보딩부터 짓는 게 효율적:
1. **온보딩/프로필 입력** — 게스트 로그인(연결됨) → `POST /health-check/sessions` → 폼 입력 → `POST /health-profiles`
2. **STT 음성 입력** — 위 폼에 음성 대안으로 `/voice` 얹기 (← 3장 결정 반영)
3. **홈** — `GET /home`
4. **미션 흐름** — headless UseCase(#22/#29) 존재 → UI만 부착
5. **건강체크 건너뛰기** — `/skip` (온보딩 easy 분기)
6. **난이도 조정** — `PATCH /users/me/activity-profile`
7. **대시보드** — `stamps` / `summary` / `points`

---
### 논의에서 정할 것 (요약)
1. **파싱 위치: A / B / C** ← 가장 중요, 나머지가 여기서 갈림
2. (A/B면) 세션 다중턴 구조 변경 확인 — 백엔드 담당
3. STT 착수 시점 vs. 온보딩 폼 화면 우선순위

---

## 6. 확정 (1차) — A안 채택 + 계약 단순화

3장 논의 결과 **A(백엔드 규칙기반)** 로 확정. 단, R19의 "다중턴 세션 + 서버 confirm_prompt"는
버리고 **stateless 필드 단위 파싱**으로 단순화한다. STT는 온보딩 필수 경로가 아니라
**수동입력 폼 위에 얹는 보조 입력**이며, 먼저 `android/dev`에서 인식률·UX를 검증한 뒤
본 `dev` 도입을 결정한다.

**명세 스레드에 제안할 API (신규, 세션 무관):**

```
POST /api/v1/health-check/voice/parse
Request:  { "field": "height_cm", "raw_transcript": "백육십" }
Response: { "field": "height_cm", "value": 160.0, "needs_confirmation": true }
```

- 앱은 현재 질문의 `field`를 알고 있으므로 `field + raw_transcript`만 보낸다(앱은 파싱하지 않는다).
- 파싱 실패/모호/"몰라요" → `value=null, needs_confirmation=false` (에러 아님, **200**). 앱은 수동입력 폼으로 폴백.
- `value` 타입: 측정치 float / `birth_date` ISO 문자열 / enum·sex 문자열 / 예·아니오 bool.
- `confirm_prompt`는 서버 의존을 줄이려 **앱에서 로컬 문구**로 만든다.
- 기존 `POST /sessions/{id}/voice`(로그 저장용)는 **그대로 두고**, 위 엔드포인트를 별도로 추가한다.
- 인식 품질이 부족하면 **응답 계약을 유지한 채** 서버 파서만 A→B(LLM)로 교체 → 앱 수정 불필요.

**커버 필드/발화(1차 파서 구현·테스트 완료):**
- 키: `백육십`·`160`·`160센티`·`일미터 육십` → 160.0
- 몸무게: `오십팔`·`58키로` → 58.0 / 허리: `팔십이`·`82센티` → 82.0
- 생년월일: `1958년 3월 1일`·`오팔년 삼월 일일` → `1958-03-01` (2자리 연도는 1900년대로 확장)
- 예/아니오: `네`→true, `아니요`→false, `몰라요`·`잘 모르겠어요`→null(폼 폴백)
- 상식 범위 밖 수치(예: 키 16cm)는 오인식으로 보고 `value=null` 처리.

구현: `app/services/voice_parse.py`(파서) · `app/dtos/voice_parse.py`(계약) ·
`app/apis/v1/health_check_routers.py`(엔드포인트) · `app/tests/health_check_apis/test_voice_parse.py`.
브랜치: `backend/feature/stt-voice-parse` (본 `dev` 도입은 android/dev 검증 후 결정).

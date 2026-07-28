# 로컬 개발 환경 셋업 — 소셜 로그인 (카카오 / 구글)

> **왜 필요한가:** 카카오·구글 로그인 키는 보안상 **레포에 없고, 각자 개발 머신의 gradle 설정에서
> 주입**됩니다. 이 설정이 없으면 빌드에 키가 **빈 값**으로 들어가고, 앱은 카카오 버튼을
> **회색 비활성**으로 만듭니다(에러도 안 나서 "미구현"처럼 보임). 미구현이 아니라 **빌드 환경 설정**
> 문제입니다. 아래 두 단계만 각자 1회 하면 됩니다.

- 키 값 자체는 레포에 올리지 않습니다 → **팀 채널로 공유받은 값**을 사용하세요(값을 잃어버리면 yuka에게 요청).
- 주입 지점은 `android/app/build.gradle.kts` 의 `AH_*` gradle 프로퍼티(→ env 폴백)입니다.

---

## 1단계 — 키 주입 (각자 1회)

**`~/.gradle/gradle.properties`** (홈 디렉터리, **git 미추적**) 에 아래 3줄을 추가합니다.
파일이 없으면 새로 만드세요. Finder에서는 `Cmd+Shift+G` → `~/.gradle` 로 이동.

```properties
AH_GOOGLE_WEB_CLIENT_ID=<팀 채널로 공유받은 값>
AH_KAKAO_NATIVE_APP_KEY=<팀 채널로 공유받은 값>
AH_DEBUG_API_BASE_URL=<팀 채널로 공유받은 값>
```

> ⚠️ **반드시 홈의 `~/.gradle/gradle.properties`** 에 넣으세요.
> 레포 안의 `android/gradle.properties` 는 **git 추적 대상**이라 키를 넣으면 커밋에 섞여 유출됩니다.

저장 후 앱을 다시 빌드하면 카카오 버튼이 활성화됩니다. (값이 비면 manifest placeholder 가
`unconfigured` 로 들어가 버튼이 회색이 됩니다.)

- `AH_DEBUG_API_BASE_URL` 은 미설정 시 기본값이 **공용 배포 서버**(`https://aigo-health.duckdns.org/api/v1/`)
  라 로그인 키만 넣어도 실기기 테스트는 됩니다. 로컬 백엔드로 개발할 때만 이 값을 override 하세요.
- 값은 `-PAH_KAKAO_NATIVE_APP_KEY=...` 처럼 gradle CLI 인자나 동일 이름의 환경변수로도 주입 가능합니다.

## 2단계 — 디버그 키 해시 등록 (각자 1회)

카카오 콘솔은 **패키지명 + 서명 키 해시**를 검증합니다. 디버그 빌드는 각자 머신의
`~/.android/debug.keystore` 로 서명돼 해시가 서로 다릅니다. 아래를 실행하고 (비밀번호를 물으면 `android`):

```bash
keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore | openssl sha1 -binary | openssl base64
```

출력된 Base64 문자열을 **yuka에게 전달**하면 카카오 개발자 콘솔에 등록합니다.
등록 전까지는 버튼을 눌러도 `KOE009`(잘못된 키 해시) 에러가 뜨는 게 정상입니다.

## 구글 로그인

구글도 같은 원리로 SHA-1 지문 등록이 필요할 수 있습니다. 카카오가 되는 것을 먼저 확인하고,
구글이 안 되면 위 명령의 `sha1` 지문(콜론 구분 16진수 형식)을 yuka에게 전달하세요.

---

## 트러블슈팅

| 증상 | 원인 | 해결 |
|---|---|---|
| 카카오 버튼이 **회색 비활성** | `AH_KAKAO_NATIVE_APP_KEY` 빈 값 → placeholder `unconfigured` | 1단계 키 주입 후 재빌드 |
| 버튼은 눌리는데 `KOE009` | 디버그 키 해시 미등록 | 2단계 해시를 yuka에게 전달 → 콘솔 등록 |
| 로그인 후 네트워크/서버 오류 | `AH_DEBUG_API_BASE_URL` 오설정 | 미설정(공용 서버 기본값) 상태로 되돌리거나 올바른 로컬 주소 주입 |
| 키를 넣었는데 그대로 회색 | 레포의 `android/gradle.properties` 에 넣음 | **홈의 `~/.gradle/gradle.properties`** 로 옮기고 재빌드 |

> 참고: release 서명 정보(`AH_RELEASE_*`)도 같은 원리로 `~/.gradle/gradle.properties` 나 환경변수로만
> 주입하며 레포에 두지 않습니다(`android/app/build.gradle.kts` 참조). API 주소 상세는
> [`android/README.md`](../android/README.md).

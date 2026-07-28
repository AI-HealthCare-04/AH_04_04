# 로컬 개발 환경 셋업 — 소셜 로그인 (카카오 / 구글)

> **왜 필요한가:** 카카오·구글 로그인 키는 보안상 **레포에 없고, 각자 개발 머신의 gradle 설정에서
> 주입**됩니다. 이 설정이 없으면 빌드에 키가 **빈 값**으로 들어가고, 앱은 카카오 버튼을
> **회색 비활성**으로 만듭니다(에러도 안 나서 "미구현"처럼 보임). 미구현이 아니라 **빌드 환경 설정**
> 문제입니다. 아래 두 단계만 각자 1회 하면 됩니다.

- 키 값 자체는 레포에 올리지 않습니다 → **팀 채널로 공유받은 값**을 사용하세요(값을 잃어버리면 yuka에게 요청).
- 주입 지점은 `android/app/build.gradle.kts` 의 `AH_*` gradle 프로퍼티(→ env 폴백)입니다.

---

## 1단계 — 키 주입 (각자 1회)

**`~/.gradle/gradle.properties`** (홈 디렉터리, **git 미추적**) 에 아래를 추가합니다.
파일이 없으면 새로 만드세요.
- macOS: Finder에서 `Cmd+Shift+G` → `~/.gradle`
- Windows: 탐색기 주소창에 `%USERPROFILE%\.gradle`

### 필수 — 소셜 로그인 활성화에 반드시 필요한 2개

```properties
AH_GOOGLE_WEB_CLIENT_ID=<팀 채널로 공유받은 값>
AH_KAKAO_NATIVE_APP_KEY=<팀 채널로 공유받은 값>
```

### 선택 — 로컬 백엔드로 개발할 때만

`AH_DEBUG_API_BASE_URL` 은 **미설정 시 공용 배포(스테이징) 서버가 기본값**이라, 소셜 로그인 활성화에는
필요 없습니다. **에뮬레이터/로컬 백엔드로 개발할 때만** 아래를 추가하세요(잘못된 주소를 복사하면
로그인 후 서버 오류가 납니다).

```properties
# 예: 에뮬레이터 → 호스트 PC
AH_DEBUG_API_BASE_URL=http://10.0.2.2:8000/api/v1/
```

저장 후 앱을 다시 빌드하면 카카오 버튼이 활성화됩니다. (값이 비면 manifest placeholder 가
`unconfigured` 로 들어가 버튼이 회색이 됩니다.) 값은 `-PAH_KAKAO_NATIVE_APP_KEY=...` 처럼 gradle CLI
인자나 동일 이름의 환경변수로도 주입 가능합니다.

> ⚠️ **반드시 홈의 `~/.gradle/gradle.properties`** 에 넣으세요.
> 레포 안의 `android/gradle.properties` 는 **git 추적 대상**이라 키를 넣으면 커밋에 섞여 유출됩니다.

## 2단계 — 카카오 디버그 키 해시 등록 (각자 1회)

카카오 콘솔은 **패키지명 + 서명 키 해시**를 검증합니다. 디버그 빌드는 각자 머신의
`~/.android/debug.keystore` 로 서명돼 해시가 서로 다릅니다. 아래로 **본인 머신의** 키 해시(Base64)를
뽑아 **yuka에게 값만 전달**하면 콘솔에 등록합니다. 등록 전에는 버튼을 눌러도 `KOE009`(잘못된 키 해시)가
뜨는 게 정상입니다.

**macOS / Linux / Git Bash** (`openssl` 포함):

```bash
keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore \
  -storepass android -keypass android | openssl sha1 -binary | openssl base64
```

**Windows**: PowerShell/cmd에는 기본적으로 `openssl` 이 없습니다. **Git 설치 시 함께 오는 Git Bash**를
열고 위 명령을 그대로 실행하세요(keystore 경로는 `~/.android/debug.keystore` = `%USERPROFILE%\.android\debug.keystore`).

> 🔒 출력된 **Base64 문자열(값)만** 공유하세요. `debug.keystore` **파일 자체는 공유하지 않습니다**(서명 키).

## 구글 로그인 (SHA-1 지문)

구글 콘솔은 **콜론 구분 16진수 SHA-1 지문**이 필요하며, 위 카카오 키 해시(Base64) 명령으로는 얻을 수
없습니다. 아래 Gradle `signingReport` 로 **debug variant** 의 `SHA1` 을 확인해 yuka에게 전달하세요.

- macOS / Linux: `cd android && ./gradlew signingReport`
- Windows: `cd android` 후 `.\gradlew.bat signingReport`

출력에서 `Variant: debug` 항목의 `SHA1:` 값(예: `AA:BB:CC:…:FF`)을 사용합니다. 카카오가 되는 것을 먼저
확인하고, 구글이 안 될 때 등록하면 됩니다.

---

## 트러블슈팅

| 증상 | 원인 | 해결 |
|---|---|---|
| 카카오 버튼이 **회색 비활성** | `AH_KAKAO_NATIVE_APP_KEY` 빈 값 → placeholder `unconfigured` | 1단계 키 주입 후 재빌드 |
| 버튼은 눌리는데 `KOE009` | 디버그 키 해시 미등록 | 2단계 해시(Base64)를 yuka에게 전달 → 콘솔 등록 |
| 로그인 후 네트워크/서버 오류 | `AH_DEBUG_API_BASE_URL` 오설정 | 이 줄을 지워 공용 서버 기본값으로 되돌리거나, 올바른 로컬 주소로 수정 |
| 키를 넣었는데 그대로 회색 | 레포의 `android/gradle.properties` 에 넣음 | **홈의 `~/.gradle/gradle.properties`** 로 옮기고 재빌드 |

> 참고: release 서명 정보(`AH_RELEASE_*`)도 같은 원리로 `~/.gradle/gradle.properties` 나 환경변수로만
> 주입하며 레포에 두지 않습니다(`android/app/build.gradle.kts` 참조). API 주소 상세는
> [`android/README.md`](../android/README.md).

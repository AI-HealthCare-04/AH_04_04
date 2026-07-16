# Android 환경별 API 주소 설정

앱의 API 주소는 빌드 타입별 `BuildConfig.API_BASE_URL`로 주입합니다.

## Debug

기본 주소는 Android 에뮬레이터에서 호스트 PC를 가리키는 다음 값입니다.

```text
http://10.0.2.2:8000/api/v1/
```

실기기나 다른 개발 서버를 사용할 때는 Gradle 속성 또는 환경변수로 덮어쓸 수 있습니다.

```powershell
.\gradlew.bat assembleDebug -PAH_DEBUG_API_BASE_URL=http://192.168.0.10:8000/api/v1/
```

```powershell
$env:AH_DEBUG_API_BASE_URL="http://192.168.0.10:8000/api/v1/"
.\gradlew.bat assembleDebug
```

로컬 에뮬레이터와 실기기 개발 서버를 위한 HTTP cleartext 허용 설정은 debug 빌드에만
포함됩니다. Release 빌드에는 이 예외가 포함되지 않습니다.

## Release

Release 빌드는 배포 서버 주소를 반드시 주입해야 하며, HTTPS 주소이면서 `/`로 끝나야 합니다.

```powershell
.\gradlew.bat assembleRelease -PAH_RELEASE_API_BASE_URL=https://api.example.com/api/v1/
```

```powershell
$env:AH_RELEASE_API_BASE_URL="https://api.example.com/api/v1/"
.\gradlew.bat assembleRelease
```

주소가 없거나 규칙에 맞지 않으면 release 빌드가 실패합니다. 실제 서버 주소나 비밀값은
저장소에 커밋하지 않고 CI 변수 또는 로컬 환경에서 관리합니다.

# Android 환경별 API 주소 설정

앱의 API 주소는 빌드 타입별 `BuildConfig.API_BASE_URL`로 주입합니다.

## Debug

기본 주소는 **공용 배포(스테이징) 서버**입니다. 팀 대부분이 실기기로 이 서버를 테스트하므로, 별도 설정 없이 debug 빌드해도 실기기에서 바로 붙습니다.

```text
https://aigo-health.duckdns.org/api/v1/
```

로컬 백엔드(에뮬레이터·개발 서버)로 개발할 때는 Gradle 속성 또는 환경변수로 덮어씁니다. 에뮬레이터는 호스트 PC를 `10.0.2.2`로 가리킵니다.

```powershell
.\gradlew.bat assembleDebug -PAH_DEBUG_API_BASE_URL=http://10.0.2.2:8000/api/v1/
```

```powershell
$env:AH_DEBUG_API_BASE_URL="http://10.0.2.2:8000/api/v1/"
.\gradlew.bat assembleDebug
```

> ⚠️ 기본값이 **공용 서버**이므로, 별도 설정 없이 debug로 테스트하면 그 데이터(로그인·미션 기록 등)가 **공유 스테이징 환경에 저장**됩니다. 격리가 필요하면 위처럼 로컬 백엔드로 override 하세요.

로컬 개발 서버(HTTP)를 위한 cleartext 허용 설정은 debug 빌드에만 포함됩니다. Release 빌드에는 이 예외가 포함되지 않습니다.

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

# =====================================================================================
# 약관(Terms) API 테스트 파일입니다.
# pytest가 test_ 로 시작하는 함수를 자동으로 찾아 실행합니다.
# conftest.py에 정의된 'client' 픽스처(테스트용 HTTP 클라이언트)를 주입받아 API를 호출합니다.
# =====================================================================================

from httpx import AsyncClient  # 테스트에서 우리 앱에 가짜 HTTP 요청을 보내는 클라이언트
from starlette import status  # 상태코드를 이름으로 확인하기 위한 도구


# GET /terms 는 로그인이 필요합니다. 토큰 없이 부르면 401이 나야 합니다.
async def test_get_terms_requires_auth(client: AsyncClient) -> None:
    # Authorization 헤더 없이 호출 → 인증 의존성이 DB 접근 전에 막아섭니다.
    response = await client.get("/api/v1/terms")
    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    # 공통 예외 핸들러가 명세서 규격 {"error_detail": ...} 로 응답을 통일했는지 확인합니다.
    assert "error_detail" in response.json()


# POST /users/me/agreements 도 로그인이 필요합니다. 토큰 없이 부르면 401이 나야 합니다.
async def test_agree_terms_requires_auth(client: AsyncClient) -> None:
    response = await client.post(
        "/api/v1/users/me/agreements",
        json={"agreements": [{"terms_type": "service", "version": "1.0", "agreed": True}]},
    )
    assert response.status_code == status.HTTP_401_UNAUTHORIZED


# TODO(db-core-models): terms / terms_agreements 테이블이 초기 마이그레이션으로 생성된 뒤,
# 아래 해피패스 통합 테스트를 추가합니다(로그인 토큰 발급 → 실제 DB 사용):
#   - GET  /api/v1/terms                 : 활성 약관 목록이 명세서 필드대로 반환되는지
#   - POST /api/v1/users/me/agreements   : 필수 약관 모두 동의 시 onboarding_status가 "terms_agreed"로 바뀌는지
#   - POST (필수 약관 미동의)            : 400 + "필수 약관에 동의해야 합니다." 가 반환되는지

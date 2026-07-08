# =====================================================================================
# 약관(Terms) API 테스트 파일입니다.
# pytest가 test_ 로 시작하는 함수를 자동으로 찾아 실행합니다.
# conftest.py에 정의된 'client' 픽스처(테스트용 HTTP 클라이언트)를 주입받아 API를 호출합니다.
# =====================================================================================

from httpx import AsyncClient  # 테스트에서 우리 앱에 가짜 HTTP 요청을 보내는 클라이언트
from starlette import status  # 상태코드를 이름으로 확인하기 위한 도구

from app.core.terms_catalog import TERMS_CATALOG  # 정적 약관 카탈로그(GET /terms 응답의 출처)


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


# 정적 카탈로그가 명세서 규격(GET /terms 응답 필드)을 만족하는지 검증합니다. (DB 불필요)
def test_terms_catalog_shape() -> None:
    assert len(TERMS_CATALOG) > 0
    types = {str(spec.terms_type) for spec in TERMS_CATALOG}
    # 필수 약관 3종이 카탈로그에 존재
    assert {"service", "privacy", "sensitive_health"} <= types
    for spec in TERMS_CATALOG:
        # 명세서 GET /terms 응답 필드가 모두 채워져 있어야 함
        assert spec.title and spec.version and spec.url
        assert isinstance(spec.is_required, bool)
    # 필수/선택 정책 확인
    assert next(s for s in TERMS_CATALOG if str(s.terms_type) == "service").is_required is True
    assert next(s for s in TERMS_CATALOG if str(s.terms_type) == "marketing").is_required is False


# TODO(test-db-fixture): DB 세션 픽스처(테스트 DB + 트랜잭션 롤백)가 준비되면
# 아래 해피패스 통합 테스트를 추가합니다(로그인 토큰 발급 → 실제 terms_agreements 저장):
#   - GET  /api/v1/terms                 : 인증 사용자에게 카탈로그 목록이 명세서 필드대로 반환
#   - POST /api/v1/users/me/agreements   : 필수 약관 모두 동의 시 onboarding_status가 "terms_agreed"로 변경
#   - POST (필수 약관 미동의)            : 400 + "필수 약관에 동의해야 합니다."
#   - POST (구버전 version 제출)          : 409 + "약관이 변경되었습니다. ..."
#   - POST (catalog에 없는 terms_type)   : 400 + "존재하지 않는 약관이 포함되어 있습니다."

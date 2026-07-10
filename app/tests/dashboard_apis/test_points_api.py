# =====================================================================================
# 포인트 조회 API 라우터 테스트 — 인증 가드. DB 불필요.
# GET /users/me/points는 이제 인증이 필요하다(스텁에서 실제 구현으로 전환).
# =====================================================================================
from httpx import AsyncClient
from starlette import status


async def test_points_requires_authentication(client: AsyncClient) -> None:
    # 토큰 없이 호출하면 401 + 명세 규격 한글 메시지여야 한다.
    response = await client.get("/api/v1/users/me/points")
    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    assert response.json() == {"error_detail": "인증이 필요합니다."}

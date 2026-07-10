# =====================================================================================
# 대시보드 시각화 API 라우터 테스트 — 인증 가드. DB 불필요.
# GET /dashboard/summary는 이제 인증이 필요하다(스텁에서 실제 구현으로 전환).
# =====================================================================================
from httpx import AsyncClient
from starlette import status


async def test_summary_requires_authentication(client: AsyncClient) -> None:
    response = await client.get("/api/v1/dashboard/summary")
    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    assert response.json() == {"error_detail": "인증이 필요합니다."}

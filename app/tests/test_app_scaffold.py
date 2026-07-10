from httpx import AsyncClient
from starlette import status


async def test_health_check(client: AsyncClient) -> None:
    response = await client.get("/health")
    assert response.status_code == status.HTTP_200_OK
    assert response.json() == {"status": "ok"}


async def test_missions_requires_authentication(client: AsyncClient) -> None:
    # GET /missions는 이제 인증이 필요하다(명세 v7.1: 성공 200 / 미인증 401).
    # 토큰 없이 호출하면 401 + 명세 규격 한글 메시지여야 한다.
    response = await client.get("/api/v1/missions?status=available")
    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    assert response.json() == {"error_detail": "인증이 필요합니다."}

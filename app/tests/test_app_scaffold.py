from httpx import AsyncClient
from starlette import status


async def test_health_check(client: AsyncClient) -> None:
    response = await client.get("/health")
    assert response.status_code == status.HTTP_200_OK
    assert response.json() == {"status": "ok"}


async def test_missions_available_filter_scaffold(client: AsyncClient) -> None:
    response = await client.get("/api/v1/missions?status=available")
    assert response.status_code == status.HTTP_200_OK
    assert response.json()["filters"]["status"] == "available"

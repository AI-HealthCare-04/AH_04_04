from fastapi import APIRouter, status

health_profile_router = APIRouter(prefix="/health-profiles", tags=["health-profiles"])


@health_profile_router.get("/me/latest", status_code=status.HTTP_200_OK)
async def get_latest_health_profile() -> dict[str, str]:
    return {"detail": "latest health profile scaffold"}


@health_profile_router.post("", status_code=status.HTTP_201_CREATED)
async def create_health_profile() -> dict[str, str]:
    return {"detail": "create health profile scaffold"}

from fastapi import APIRouter, status

activity_profile_router = APIRouter(prefix="/users/me/activity-profile", tags=["activity-profile"])


@activity_profile_router.get("", status_code=status.HTTP_200_OK)
async def get_activity_profile() -> dict[str, str]:
    return {"detail": "activity profile scaffold"}


@activity_profile_router.patch("", status_code=status.HTTP_200_OK)
async def update_activity_profile() -> dict[str, str]:
    return {"detail": "activity profile update scaffold"}

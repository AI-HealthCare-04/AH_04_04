from fastapi import APIRouter, status

mission_router = APIRouter(prefix="/missions", tags=["missions"])


@mission_router.get("", status_code=status.HTTP_200_OK)
async def get_missions(status: str | None = None, mission_type: str | None = None, level: str | None = None) -> dict:
    return {"missions": [], "filters": {"status": status, "mission_type": mission_type, "level": level}}

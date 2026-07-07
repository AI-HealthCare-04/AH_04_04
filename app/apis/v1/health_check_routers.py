from fastapi import APIRouter, status

health_check_router = APIRouter(prefix="/health-check", tags=["health-check"])


@health_check_router.post("/sessions", status_code=status.HTTP_201_CREATED)
async def start_health_check_session() -> dict[str, str]:
    return {"detail": "health check session scaffold"}


@health_check_router.post("/sessions/{session_id}/voice", status_code=status.HTTP_200_OK)
async def parse_health_check_voice(session_id: int) -> dict[str, int | str]:
    return {"session_id": session_id, "detail": "voice confirmation scaffold"}


@health_check_router.post("/sessions/{session_id}/skip", status_code=status.HTTP_200_OK)
async def skip_health_check(session_id: int) -> dict[str, int | str]:
    return {"session_id": session_id, "status": "skipped"}

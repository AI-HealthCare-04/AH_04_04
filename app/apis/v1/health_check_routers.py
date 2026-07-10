from typing import Annotated

from fastapi import APIRouter, Depends, status

from app.dependencies.security import get_request_user
from app.dtos.voice_parse import VoiceParseRequest, VoiceParseResponse
from app.models.users import User
from app.services.voice_parse import VoiceParseService

health_check_router = APIRouter(prefix="/health-check", tags=["health-check"])


@health_check_router.post("/sessions", status_code=status.HTTP_201_CREATED)
async def start_health_check_session() -> dict[str, str]:
    return {"detail": "health check session scaffold"}


@health_check_router.post("/voice/parse", response_model=VoiceParseResponse, status_code=status.HTTP_200_OK)
async def parse_voice_field(
    data: VoiceParseRequest,
    user: Annotated[User, Depends(get_request_user)],
) -> VoiceParseResponse:
    # 수동입력 폼 보조용 stateless 파서. 세션/DB를 건드리지 않고 field+raw_transcript만 해석한다.
    return VoiceParseService.parse(data.field, data.raw_transcript)


@health_check_router.post("/sessions/{session_id}/voice", status_code=status.HTTP_200_OK)
async def parse_health_check_voice(session_id: int) -> dict[str, int | str]:
    return {"session_id": session_id, "detail": "voice confirmation scaffold"}


@health_check_router.post("/sessions/{session_id}/skip", status_code=status.HTTP_200_OK)
async def skip_health_check(session_id: int) -> dict[str, int | str]:
    return {"session_id": session_id, "status": "skipped"}

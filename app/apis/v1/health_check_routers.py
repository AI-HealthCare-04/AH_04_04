from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.health_check import (
    HealthCheckSessionCreateRequest,
    HealthCheckSessionResponse,
    HealthCheckSkipResponse,
)
from app.dtos.voice_parse import VoiceParseRequest, VoiceParseResponse
from app.models.users import User
from app.services.health_check import HealthCheckService

health_check_router = APIRouter(prefix="/health-check", tags=["health-check"])


@health_check_router.post("/sessions", response_model=HealthCheckSessionResponse, status_code=status.HTTP_201_CREATED)
async def start_health_check_session(
    data: HealthCheckSessionCreateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> HealthCheckSessionResponse:
    return await HealthCheckService(session).start_session(user, data)


@health_check_router.post(
    "/sessions/{session_id}/voice",
    response_model=VoiceParseResponse,
    status_code=status.HTTP_200_OK,
)
async def parse_health_check_voice(
    session_id: int,
    data: VoiceParseRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> VoiceParseResponse:
    # 음성 재확인(보조입력): 원문을 세션에 저장하고 field에 대한 파싱 결과를 반환한다(세션 종료 아님).
    return await HealthCheckService(session).parse_voice(user, session_id, data)


@health_check_router.post(
    "/sessions/{session_id}/skip",
    response_model=HealthCheckSkipResponse,
    status_code=status.HTTP_200_OK,
)
async def skip_health_check(
    session_id: int,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> HealthCheckSkipResponse:
    return await HealthCheckService(session).skip_session(user, session_id)

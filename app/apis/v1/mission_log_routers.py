# =====================================================================================
# Mission Log 라우터
#   - POST  /api/v1/mission-logs           : 미션 로그 생성 (운동/걷기 시작 or 식사/게임 즉시완료)
#   - PATCH /api/v1/mission-logs/{id}       : 미션 로그 수정 (운동 완료 / 걷기 종료)
#   - GET   /api/v1/mission-logs            : 미션 로그 조회 (일자별)
# =====================================================================================
from datetime import date as date_type
from typing import Annotated

from fastapi import APIRouter, Depends, Query
from fastapi import status as http_status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.mission import (
    MissionLogCreateRequest,
    MissionLogCreateResponse,
    MissionLogListItem,
    MissionLogListResponse,
    MissionLogUpdateRequest,
    MissionLogUpdateResponse,
)
from app.models.users import User
from app.services.mission import MissionService

mission_log_router = APIRouter(prefix="/mission-logs", tags=["mission-logs"])


@mission_log_router.post("", response_model=MissionLogCreateResponse, status_code=http_status.HTTP_201_CREATED)
async def create_mission_log(
    data: MissionLogCreateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> MissionLogCreateResponse:
    return await MissionService(session).create_mission_log(user=user, data=data)


@mission_log_router.patch(
    "/{mission_log_id}", response_model=MissionLogUpdateResponse, status_code=http_status.HTTP_200_OK
)
async def update_mission_log(
    mission_log_id: int,
    data: MissionLogUpdateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> MissionLogUpdateResponse:
    return await MissionService(session).update_mission_log(user=user, mission_log_id=mission_log_id, data=data)


@mission_log_router.get("", response_model=MissionLogListResponse, status_code=http_status.HTTP_200_OK)
async def get_mission_logs(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    date: Annotated[date_type | None, Query()] = None,
) -> MissionLogListResponse:
    logs = await MissionService(session).list_mission_logs(user=user, on_date=date)
    return MissionLogListResponse(
        logs=[
            MissionLogListItem(
                mission_log_id=log.mission_log_id,
                mission_type=log.mission_type.value,
                success=log.success,
                counted_for_daily=log.counted_for_daily,
                earned_points=log.earned_points,
            )
            for log in logs
        ]
    )

# =====================================================================================
# Mission 라우터 — GET /api/v1/missions (수행 가능한 미션 목록)
# =====================================================================================
from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi import status as http_status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.mission import MissionListResponse
from app.models.enums import ActivityLevel, MissionType
from app.models.users import User
from app.services.mission import MissionService

mission_router = APIRouter(prefix="/missions", tags=["missions"])


@mission_router.get("", response_model=MissionListResponse, status_code=http_status.HTTP_200_OK)
async def get_missions(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    # 쿼리 필터(모두 optional). status는 명세상 "available" 고정이라 현재 로직엔 미사용.
    status: str | None = None,
    mission_type: MissionType | None = None,
    level: ActivityLevel | None = None,
) -> MissionListResponse:
    missions = await MissionService(session).get_missions(user=user, mission_type=mission_type, level=level)
    return MissionListResponse(missions=missions)

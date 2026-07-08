# =====================================================================================
# Sensor 라우터 — POST /api/v1/sensor-sessions (센서 세션 데이터 전송)
# =====================================================================================
from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi import status as http_status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.sensor import SensorSessionCreateRequest, SensorSessionCreateResponse
from app.models.users import User
from app.services.sensor import SensorService

sensor_router = APIRouter(prefix="/sensor-sessions", tags=["sensor-sessions"])


@sensor_router.post("", response_model=SensorSessionCreateResponse, status_code=http_status.HTTP_201_CREATED)
async def create_sensor_session(
    data: SensorSessionCreateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> SensorSessionCreateResponse:
    return await SensorService(session).create_sensor_session(user=user, data=data)

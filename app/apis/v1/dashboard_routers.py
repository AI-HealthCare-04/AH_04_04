from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.dashboard import HomeResponse, PointsResponse, StampsResponse
from app.models.users import User
from app.services.dashboard import DashboardService

dashboard_router = APIRouter(tags=["dashboard"])


@dashboard_router.get("/home", response_model=HomeResponse, status_code=status.HTTP_200_OK)
async def get_home(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> HomeResponse:
    return await DashboardService(session).get_home(user)


@dashboard_router.get("/dashboard/stamps", response_model=StampsResponse, status_code=status.HTTP_200_OK)
async def get_stamps(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    # 명세 §30: month 누락 시 FastAPI 기본 422가 아니라 400을 내기 위해 optional로 받고 수동 검증한다.
    month: str | None = None,
) -> StampsResponse:
    if not month:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="month 파라미터가 필요합니다.")
    return await DashboardService(session).get_stamps(user, month)


@dashboard_router.get("/dashboard/summary", status_code=status.HTTP_200_OK)
async def get_dashboard_summary(days: int = 14) -> dict:
    return {"range_days": days, "activity_trend": [], "lifestyle_records": {}, "risk_change": []}


@dashboard_router.get("/users/me/points", response_model=PointsResponse, status_code=status.HTTP_200_OK)
async def get_points(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> PointsResponse:
    # 포인트 잔액·적립 이력 조회(인증 필요). 사용 이력(point_spend_logs)은 v6.0에서 제거되어 미노출.
    return await DashboardService(session).get_points(user)

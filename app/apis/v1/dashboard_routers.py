from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.dashboard import HomeResponse
from app.models.users import User
from app.services.dashboard import DashboardService

dashboard_router = APIRouter(tags=["dashboard"])


@dashboard_router.get("/home", response_model=HomeResponse, status_code=status.HTTP_200_OK)
async def get_home(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> HomeResponse:
    return await DashboardService(session).get_home(user)


@dashboard_router.get("/dashboard/stamps", status_code=status.HTTP_200_OK)
async def get_stamps(month: str) -> dict:
    return {"month": month, "days": []}


@dashboard_router.get("/dashboard/summary", status_code=status.HTTP_200_OK)
async def get_dashboard_summary(days: int = 14) -> dict:
    return {"range_days": days, "activity_trend": [], "lifestyle_records": {}, "risk_change": []}


@dashboard_router.get("/users/me/points", status_code=status.HTTP_200_OK)
async def get_points() -> dict:
    # 명세 §32: v6.0에서 point_spend_logs 제거 → 사용 이력(spend_logs) 미노출. 잔액+적립 이력만 반환.
    return {"current_points": 0, "earn_logs": []}

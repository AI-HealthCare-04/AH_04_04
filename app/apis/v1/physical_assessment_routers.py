from typing import Annotated

from fastapi import APIRouter, Depends, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.physical_assessment import (
    PhysicalAssessmentCreateRequest,
    PhysicalAssessmentHistoryResponse,
    PhysicalAssessmentResponse,
)
from app.models.users import User
from app.services.physical_assessment import PhysicalAssessmentService

physical_assessment_router = APIRouter(prefix="/physical-assessments", tags=["physical-assessments"])


@physical_assessment_router.post("", response_model=PhysicalAssessmentResponse, status_code=status.HTTP_201_CREATED)
async def create_physical_assessment(
    data: PhysicalAssessmentCreateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> PhysicalAssessmentResponse:
    return await PhysicalAssessmentService(session).create_assessment(user, data)


@physical_assessment_router.get(
    "/me/history",
    response_model=PhysicalAssessmentHistoryResponse,
    status_code=status.HTTP_200_OK,
)
async def get_physical_assessment_history(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
) -> PhysicalAssessmentHistoryResponse:
    # #353: 5STS 측정 이력(최신순, 측정 기록만). 추이 표시·변화 문구는 앱 소관(#57 비의료 — 판정 미포함).
    return await PhysicalAssessmentService(session).get_measured_history(user, limit)

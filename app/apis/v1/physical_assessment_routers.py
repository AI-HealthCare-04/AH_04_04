from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.physical_assessment import PhysicalAssessmentCreateRequest, PhysicalAssessmentResponse
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

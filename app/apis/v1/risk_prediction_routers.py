from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.risk_prediction import RiskPredictionCreateRequest, RiskPredictionResponse
from app.models.users import User
from app.services.risk_prediction import RiskPredictionService

risk_prediction_router = APIRouter(prefix="/risk-predictions", tags=["risk-predictions"])


@risk_prediction_router.get("/me/latest", response_model=RiskPredictionResponse, status_code=status.HTTP_200_OK)
async def get_latest_risk_prediction(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> RiskPredictionResponse:
    return await RiskPredictionService(session).get_latest_prediction(user)


@risk_prediction_router.post("", response_model=RiskPredictionResponse, status_code=status.HTTP_201_CREATED)
async def create_risk_prediction(
    data: RiskPredictionCreateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> RiskPredictionResponse:
    return await RiskPredictionService(session).create_prediction(user, data)


@risk_prediction_router.post("/reassess", response_model=RiskPredictionResponse, status_code=status.HTTP_201_CREATED)
async def reassess_risk_prediction(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> RiskPredictionResponse:
    return await RiskPredictionService(session).reassess_latest_profile(user)

from typing import Annotated

from fastapi import APIRouter, Depends, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.risk_prediction import (
    RiskPredictionCreateRequest,
    RiskPredictionCreateResponse,
    RiskPredictionHistoryResponse,
    RiskPredictionReassessRequest,
    RiskPredictionReassessResponse,
    RiskPredictionResponse,
)
from app.models.users import User
from app.services.risk_prediction import RiskPredictionService

risk_prediction_router = APIRouter(prefix="/risk-predictions", tags=["risk-predictions"])


@risk_prediction_router.get("/me/latest", response_model=RiskPredictionResponse, status_code=status.HTTP_200_OK)
async def get_latest_risk_prediction(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> RiskPredictionResponse:
    return await RiskPredictionService(session).get_latest_prediction(user)


@risk_prediction_router.get(
    "/me/history",
    response_model=RiskPredictionHistoryResponse,
    status_code=status.HTTP_200_OK,
)
async def get_risk_prediction_history(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    limit: Annotated[int, Query(ge=1, le=30)] = 7,
) -> RiskPredictionHistoryResponse:
    # `_13 나의 기록`용 연속 추이. risk_score와 비교 가능한 변화량은 공개하되,
    # 내부 등급·모델 버전은 숨기고 서버가 비교 상태를 판정한다.
    return await RiskPredictionService(session).get_recent_predictions(user, limit)


@risk_prediction_router.post("", response_model=RiskPredictionCreateResponse, status_code=status.HTTP_201_CREATED)
async def create_risk_prediction(
    data: RiskPredictionCreateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> RiskPredictionCreateResponse:
    return await RiskPredictionService(session).create_prediction(user, data)


@risk_prediction_router.post(
    "/reassess",
    response_model=RiskPredictionReassessResponse,
    status_code=status.HTTP_201_CREATED,
)
async def reassess_risk_prediction(
    data: RiskPredictionReassessRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> RiskPredictionReassessResponse:
    return await RiskPredictionService(session).reassess_latest_profile(user, data)

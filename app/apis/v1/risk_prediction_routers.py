from typing import Annotated

from fastapi import APIRouter, Depends, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.risk_prediction import (
    CohortDistributionResponse,
    PredictionFeedbackRequest,
    PredictionFeedbackResponse,
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
    "/me/cohort-distribution",
    response_model=CohortDistributionResponse,
    status_code=status.HTTP_200_OK,
)
async def get_cohort_distribution(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> CohortDistributionResponse:
    # #193 또래 분포 병합 차트: 온보딩 결과 위험도 카드가 quantiles·density·내 위치(lower_count)를 소비한다.
    return await RiskPredictionService(session).get_cohort_distribution(user)


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


@risk_prediction_router.put(
    "/{prediction_id}/feedback",
    response_model=PredictionFeedbackResponse,
    status_code=status.HTTP_200_OK,
)
async def submit_prediction_feedback(
    prediction_id: int,
    data: PredictionFeedbackRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> PredictionFeedbackResponse:
    # #357 옵션 B: 예측 결과 체감 피드백. PUT 멱등 계약이라 모바일 재시도에 안전하고,
    # prediction_id UNIQUE 로 예측당 응답 1회가 보장된다(지영 리뷰).
    return await RiskPredictionService(session).submit_feedback(user, prediction_id, data)


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

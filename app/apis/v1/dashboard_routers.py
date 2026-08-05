from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.dashboard import (
    ChallengeTotalsResponse,
    DashboardPredictionInputs,
    HomeResponse,
    ScoreSimulationResponse,
    StampsResponse,
    WalkingDailyResponse,
)
from app.models.users import User
from app.services.dashboard import DashboardService

dashboard_router = APIRouter(tags=["dashboard"])


@dashboard_router.get("/home", response_model=HomeResponse, status_code=status.HTTP_200_OK)
async def get_home(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> HomeResponse:
    return await DashboardService(session).get_home(user)


@dashboard_router.get(
    "/dashboard/prediction-inputs",
    response_model=DashboardPredictionInputs,
    status_code=status.HTTP_200_OK,
)
async def get_prediction_inputs(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> DashboardPredictionInputs:
    # 근감소증 예측 대시보드(#193) 개인화 초기값. 등록된 신체값 + 최근 7일 걷기/운동 요일 수.
    return await DashboardService(session).get_prediction_inputs(user)


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


@dashboard_router.get(
    "/dashboard/walking-daily", response_model=WalkingDailyResponse, status_code=status.HTTP_200_OK
)
async def get_walking_daily(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    days: int = 7,
) -> WalkingDailyResponse:
    # 기록 탭 걷기 막대(#기록탭 §5.3): 최근 days일 일별 걸음·분(걷기 없는 날은 0).
    return await DashboardService(session).get_walking_daily(user, days)


@dashboard_router.get(
    "/dashboard/score-simulation", response_model=ScoreSimulationResponse, status_code=status.HTTP_200_OK
)
async def get_score_simulation(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> ScoreSimulationResponse:
    # 기록 탭 근육 건강 정보(#기록탭 §4): 걷기/근력 일수 what-if 점수 곡선.
    return await DashboardService(session).get_score_simulation(user)


@dashboard_router.get(
    "/dashboard/challenge-totals", response_model=ChallengeTotalsResponse, status_code=status.HTTP_200_OK
)
async def get_challenge_totals(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> ChallengeTotalsResponse:
    # 기록 탭 챌린지 비율 도넛(#기록탭 §5.4): 유형별 완료 일수(모든 유형 하루 1회 상한, 0회 유형 포함).
    return await DashboardService(session).get_challenge_totals(user)

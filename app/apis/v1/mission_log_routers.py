# =====================================================================================
# Mission Log 라우터
#   - POST  /api/v1/mission-logs           : 미션 로그 생성 (운동/걷기 시작 or 식사/게임 즉시완료)
#         201 생성 / 200 이미 기록된 수행의 재전송(#91)
#   - PATCH /api/v1/mission-logs/{id}       : 미션 로그 수정 (운동 완료 / 걷기 종료)
#   - GET   /api/v1/mission-logs            : 미션 로그 조회 (일자별)
# =====================================================================================
from datetime import date as date_type
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from fastapi import status as http_status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.mission import (
    MissionLogCreateRequest,
    MissionLogCreateResponse,
    MissionLogListItem,
    MissionLogListResponse,
    MissionLogUpdateRequest,
    MissionLogUpdateResponse,
)
from app.models.users import User
from app.services.mission import MissionService

mission_log_router = APIRouter(prefix="/mission-logs", tags=["mission-logs"])

# 기록 탭 기간 조회 상한(리뷰 #272). 달력 1개월·선그래프 14일을 넉넉히 덮되 과대 조회를 막는다.
_MAX_RANGE_DAYS = 92


def _validate_mission_log_query(
    date: date_type | None, date_from: date_type | None, date_to: date_type | None
) -> None:
    """조회 파라미터 방어(리뷰 #272-3): date 와 from/to 혼용 금지, from<=to, 기간 상한."""
    has_range = date_from is not None or date_to is not None
    if date is not None and has_range:
        raise HTTPException(status_code=http_status.HTTP_400_BAD_REQUEST, detail="date 와 from/to 는 함께 쓸 수 없습니다.")
    if has_range and (date_from is None or date_to is None):
        raise HTTPException(status_code=http_status.HTTP_400_BAD_REQUEST, detail="from 과 to 를 함께 지정해 주세요.")
    if date_from is not None and date_to is not None:
        if date_from > date_to:
            raise HTTPException(status_code=http_status.HTTP_400_BAD_REQUEST, detail="from 은 to 보다 늦을 수 없습니다.")
        if (date_to - date_from).days + 1 > _MAX_RANGE_DAYS:
            raise HTTPException(
                status_code=http_status.HTTP_400_BAD_REQUEST,
                detail=f"조회 기간은 최대 {_MAX_RANGE_DAYS}일입니다.",
            )


@mission_log_router.post("", response_model=MissionLogCreateResponse, status_code=http_status.HTTP_201_CREATED)
async def create_mission_log(
    data: MissionLogCreateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    response: Response,
) -> MissionLogCreateResponse:
    result = await MissionService(session).create_mission_log(user=user, data=data)
    # 재전송이라 새로 만들지 않았으면 201(Created)이 아니라 200으로 답한다.
    #   앱은 둘 다 성공으로 보고 outbox 에서 제거하면 된다(#91).
    if result.deduplicated:
        response.status_code = http_status.HTTP_200_OK
    return result


@mission_log_router.patch(
    "/{mission_log_id}", response_model=MissionLogUpdateResponse, status_code=http_status.HTTP_200_OK
)
async def update_mission_log(
    mission_log_id: int,
    data: MissionLogUpdateRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> MissionLogUpdateResponse:
    return await MissionService(session).update_mission_log(user=user, mission_log_id=mission_log_id, data=data)


@mission_log_router.get("", response_model=MissionLogListResponse, status_code=http_status.HTTP_200_OK)
async def get_mission_logs(
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
    date: Annotated[date_type | None, Query()] = None,
    date_from: Annotated[date_type | None, Query(alias="from")] = None,
    date_to: Annotated[date_type | None, Query(alias="to")] = None,
) -> MissionLogListResponse:
    # 기록 탭 달력·일별 추이(#기록탭 §5.1/§5.2): 단일일(date) 하위호환 + 기간(from~to, 포함) 조회.
    #   응답에 미션명(title)·완료시각(completed_at)을 실어 달력 바텀시트·선그래프가 바로 쓴다.
    _validate_mission_log_query(date, date_from, date_to)
    rows = await MissionService(session).list_mission_logs_detailed(
        user=user, on_date=date, date_from=date_from, date_to=date_to
    )
    return MissionLogListResponse(
        logs=[
            MissionLogListItem(
                mission_log_id=log.mission_log_id,
                mission_type=log.mission_type.value,
                title=title,
                completed_at=completed_at,
                success=log.success,
                counted_for_daily=log.counted_for_daily,
                earned_points=log.earned_points,
            )
            for log, title, completed_at in rows
        ]
    )

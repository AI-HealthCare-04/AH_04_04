# =====================================================================================
# 클라이언트 텔레메트리 라우터
#   - POST /api/v1/events/sts-overlay-shown : 근력 기능 안전망 카드(§3.4) 노출 이벤트 수집
# =====================================================================================
from decimal import Decimal
from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi import status as http_status
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db.session import get_db_session
from app.dependencies.security import get_request_user
from app.dtos.analytics import StsOverlayShownRequest
from app.models.analytics import StsOverlayEvent
from app.models.users import User
from app.repositories.analytics_repository import AnalyticsRepository

analytics_router = APIRouter(prefix="/events", tags=["events"])


@analytics_router.post("/sts-overlay-shown", status_code=http_status.HTTP_201_CREATED)
async def record_sts_overlay_shown(
    data: StsOverlayShownRequest,
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> dict[str, bool]:
    # 발화율 관측(#기록탭 §3.4 필수): 카드 노출 시 1건 저장. 실패해도 화면을 막지 않도록 앱은 fire-and-forget.
    event = StsOverlayEvent(
        user_id=user.user_id,
        tier=data.tier,
        sts_sec=Decimal(str(data.sts_sec)) if data.sts_sec is not None else None,
        bmi=Decimal(str(data.bmi)) if data.bmi is not None else None,
        score_band=data.score_band,
    )
    await AnalyticsRepository(session).add_sts_overlay_event(event)
    await session.commit()
    return {"recorded": True}

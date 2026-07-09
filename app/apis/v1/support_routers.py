from typing import Annotated

from fastapi import APIRouter, Depends, status

from app.core import config
from app.dependencies.security import get_request_user
from app.dtos.users import SupportResponse
from app.models.users import User

support_router = APIRouter(tags=["support"])


@support_router.get("/support", response_model=SupportResponse, status_code=status.HTTP_200_OK)
async def get_support(
    user: Annotated[User, Depends(get_request_user)],
) -> SupportResponse:
    # 정적 설정값(고객센터 이메일)만 반환하므로 service/repository 계층은 두지 않는다.
    return SupportResponse(email=config.SUPPORT_EMAIL)

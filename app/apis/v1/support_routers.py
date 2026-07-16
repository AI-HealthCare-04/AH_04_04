from typing import Annotated

from fastapi import APIRouter, Depends, status

from app.core import config
from app.core.faq_catalog import FAQ_CATALOG
from app.dependencies.security import get_request_user
from app.dtos.faq import FaqItemResponse, FaqListResponse
from app.dtos.users import SupportResponse
from app.models.users import User

support_router = APIRouter(tags=["support"])


@support_router.get("/support", response_model=SupportResponse, status_code=status.HTTP_200_OK)
async def get_support(
    user: Annotated[User, Depends(get_request_user)],
) -> SupportResponse:
    # 정적 설정값(고객센터 이메일)만 반환하므로 service/repository 계층은 두지 않는다.
    return SupportResponse(email=config.SUPPORT_EMAIL)


@support_router.get("/support/faqs", response_model=FaqListResponse, status_code=status.HTTP_200_OK)
async def get_faqs(
    user: Annotated[User, Depends(get_request_user)],
) -> FaqListResponse:
    # 정적 카탈로그(자주 묻는 질문)를 faq_id 순으로 반환한다. DB·service 계층 없음(정적 콘텐츠).
    ordered = sorted(FAQ_CATALOG, key=lambda spec: spec.faq_id)
    return FaqListResponse(faqs=[FaqItemResponse.model_validate(spec) for spec in ordered])

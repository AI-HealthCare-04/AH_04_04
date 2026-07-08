# =====================================================================================
# Router(라우터) 파일입니다.
# "어떤 URL + HTTP 메서드"가 "어떤 함수"에 연결되는지를 정의하는 API 진입점입니다.
# 실제 처리는 Service에게 넘기고, 여기서는 입력을 받아 Service를 호출하고 결과를 응답으로 포장합니다.
# =====================================================================================

# Annotated: FastAPI에서 "이 값은 이런 의존성으로 주입받는다"를 표현할 때 쓰는 타입 도구입니다.
from typing import Annotated

# APIRouter : 관련된 API들을 묶는 라우터 객체
# Depends   : "의존성 주입" - 함수 실행 전에 필요한 것(로그인 사용자, DB 세션 등)을 자동으로 준비
# status    : 200, 201 같은 상태코드를 이름으로 사용
from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

# get_db_session   : 요청마다 DB 세션을 하나 만들어 넣어주는 의존성
from app.core.db.session import get_db_session

# get_request_user : Authorization 헤더의 토큰을 검증해 '현재 로그인한 사용자'를 돌려주는 의존성
#                    (토큰이 없거나 잘못되면 여기서 401 에러가 나며, 함수 본문은 실행되지 않습니다.)
from app.dependencies.security import get_request_user

# 이 API가 사용하는 요청/응답 DTO들
from app.dtos.terms import TermResponse, TermsAgreementRequest, TermsAgreementResponse, TermsListResponse
from app.models.users import User
from app.services.terms import TermsService

# 약관 관련 API들을 묶는 라우터입니다. tags=["terms"]는 자동 문서(/api/docs)에서의 분류 이름입니다.
terms_router = APIRouter(tags=["terms"])


# -------------------------------------------------------------------------------------
# [GET /api/v1/terms] 약관 목록·버전 조회
# 명세서 기준: 로그인 필요(Bearer 토큰), 성공 200 / 미인증 401
# -------------------------------------------------------------------------------------
@terms_router.get("/terms", response_model=TermsListResponse, status_code=status.HTTP_200_OK)
async def get_terms(
    # 로그인한 사용자만 접근 가능하도록 인증 의존성을 겁니다.
    # (조회에 user 값을 직접 쓰진 않지만, 인증 통과 여부를 강제하기 위해 선언합니다.)
    user: Annotated[User, Depends(get_request_user)],
    # 이 요청에서 사용할 DB 세션을 주입받습니다.
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> TermsListResponse:
    # 서비스에게 현재 유효한 약관 목록(정적 catalog)을 요청합니다.
    terms = await TermsService(session).get_active_terms()
    # 카탈로그 항목(TermSpec) 리스트를 응답 DTO(TermResponse) 리스트로 변환해 감싸서 반환합니다.
    # model_validate: 속성을 가진 객체 → Pydantic 응답 모델로 변환 (base의 from_attributes=True 덕분에 가능)
    return TermsListResponse(terms=[TermResponse.model_validate(term) for term in terms])


# -------------------------------------------------------------------------------------
# [POST /api/v1/users/me/agreements] 약관 동의 제출
# 명세서 기준: 로그인 필요, 성공 200 / 필수 약관 미동의 400 / 미인증 401
# -------------------------------------------------------------------------------------
@terms_router.post(
    "/users/me/agreements",
    response_model=TermsAgreementResponse,
    status_code=status.HTTP_200_OK,  # 명세서가 200 OK를 요구합니다(생성이지만 201이 아님에 주의).
)
async def agree_terms(
    # 요청 바디(JSON)를 TermsAgreementRequest 형태로 받아 자동 검증합니다.
    agreement_data: TermsAgreementRequest,
    # 현재 로그인한 사용자(누가 동의하는지)를 주입받습니다.
    user: Annotated[User, Depends(get_request_user)],
    session: Annotated[AsyncSession, Depends(get_db_session)],
) -> TermsAgreementResponse:
    # 서비스에게 동의 처리를 맡깁니다. 처리 후 상태가 갱신된 user를 돌려받습니다.
    updated_user = await TermsService(session).agree_terms(user=user, data=agreement_data)
    # 명세서 응답 스펙에 맞춰 onboarding_status만 담아 반환합니다.
    return TermsAgreementResponse(onboarding_status=updated_user.onboarding_status)

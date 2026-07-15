# =====================================================================================
# Router(라우터) 파일입니다.
# "어떤 URL + HTTP 메서드"가 "어떤 함수"에 연결되는지를 정의하는 API 진입점입니다.
# 실제 처리는 Service에게 넘기고, 여기서는 입력을 받아 Service를 호출하고 결과를 응답으로 포장합니다.
# =====================================================================================

# Annotated: FastAPI에서 "이 값은 이런 의존성으로 주입받는다"를 표현할 때 쓰는 타입 도구입니다.
from typing import Annotated

# APIRouter : 관련된 API들을 묶는 라우터 객체
# Depends   : "의존성 주입" - 함수 실행 전에 필요한 것(로그인 사용자 등)을 자동으로 준비
# status    : 200 같은 상태코드를 이름으로 사용
from fastapi import APIRouter, Depends, status

# get_request_user : Authorization 헤더의 토큰을 검증해 '현재 로그인한 사용자'를 돌려주는 의존성
#                    (토큰이 없거나 잘못되면 여기서 401 에러가 나며, 함수 본문은 실행되지 않습니다.)
from app.dependencies.security import get_request_user

# 이 API가 사용하는 응답 DTO와 서비스.
from app.dtos.daily_tip import DailyTipResponse
from app.models.users import User
from app.services.daily_tip import DailyTipService

# 데일리 팁 API를 묶는 라우터입니다. tags=["daily-tips"]는 자동 문서(/api/docs)에서의 분류 이름입니다.
daily_tip_router = APIRouter(tags=["daily-tips"])


# -------------------------------------------------------------------------------------
# [GET /api/v1/daily-tips] 오늘의 팁 조회 (화면 `_11` 미션 목록 상단)
# 로그인 필요(Bearer 토큰): 앱의 모든 홈/미션 화면과 동일하게 인증 사용자에게만 제공.
#   성공 200 / 미인증 401.
# 콘텐츠는 사용자별로 다르지 않지만(정적 카탈로그), 접근 정책은 다른 화면과 통일합니다.
# -------------------------------------------------------------------------------------
@daily_tip_router.get("/daily-tips", response_model=DailyTipResponse, status_code=status.HTTP_200_OK)
async def get_daily_tip(
    # 로그인한 사용자만 접근 가능하도록 인증 의존성을 겁니다.
    # (팁 내용에 user 값을 직접 쓰진 않지만, 인증 통과를 강제하기 위해 선언합니다.)
    user: Annotated[User, Depends(get_request_user)],
) -> DailyTipResponse:
    # 서비스에게 "오늘의 팁"(KST 날짜 기준 순환 선택)을 요청합니다. DB 접근은 없습니다.
    return DailyTipService().get_today_tip()

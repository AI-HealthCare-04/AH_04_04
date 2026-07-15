# =====================================================================================
# Router(라우터) 파일입니다.
# "어떤 URL + HTTP 메서드"가 "어떤 함수"에 연결되는지를 정의하는 API 진입점입니다.
# 실제 처리는 Service에게 넘기고, 여기서는 입력을 받아 Service를 호출하고 결과를 응답으로 포장합니다.
# =====================================================================================

from typing import Annotated

from fastapi import APIRouter, Depends, status

# get_request_user : Authorization 헤더의 토큰을 검증해 '현재 로그인한 사용자'를 돌려주는 의존성
from app.dependencies.security import get_request_user
from app.dtos.exercise_video import ExerciseVideosResponse
from app.models.users import User
from app.services.exercise_video import ExerciseVideoService

# 운동 영상 API를 묶는 라우터입니다. tags=["exercise-videos"]는 자동 문서(/api/docs) 분류 이름.
exercise_video_router = APIRouter(tags=["exercise-videos"])


# -------------------------------------------------------------------------------------
# [GET /api/v1/exercise-videos] "운동하기" 단계별 영상 목록
# 로그인 필요(Bearer): 다른 미션/홈 화면과 동일 접근 정책. 성공 200 / 미인증 401.
# 콘텐츠는 사용자별로 다르지 않지만(정적 카탈로그) 접근 정책은 통일한다.
# -------------------------------------------------------------------------------------
@exercise_video_router.get("/exercise-videos", response_model=ExerciseVideosResponse, status_code=status.HTTP_200_OK)
async def get_exercise_videos(
    # 로그인한 사용자만 접근 가능하도록 인증 의존성을 겁니다.
    # (목록 내용에 user 값을 직접 쓰진 않지만, 인증 통과를 강제하기 위해 선언합니다.)
    user: Annotated[User, Depends(get_request_user)],
) -> ExerciseVideosResponse:
    # 서비스에게 운동 4단계 영상 목록(정적 카탈로그, order 순)을 요청합니다. DB 접근은 없습니다.
    return ExerciseVideoService().get_videos()

# =====================================================================================
# DTO(Data Transfer Object) 파일입니다.
# "API가 주고받는 데이터의 모양(형식)"을 정의합니다.
#  - Response(응답) DTO : 서버가 클라이언트(안드로이드 앱)로 돌려주는 JSON의 형태
# =====================================================================================

from pydantic import BaseModel


# -------------------------------------------------------------------------------------
# [응답] GET /exercise-videos 의 각 단계 항목. "운동하기" 화면의 단계 탭 1개에 대응.
#   불변식: available == (video_url is not None). available=false면 준비중(url·thumbnail null).
#           앱은 available=false거나 video_url이 null이면 "준비중" 처리(방어적).
# -------------------------------------------------------------------------------------
class ExerciseVideoItem(BaseModel):
    stage: str  # 단계 키: "warmup" | "seated" | "standing" | "cooldown" (앱 번들 폴백 매핑 키)
    label: str  # 화면 표시 라벨(예: "서서 운동")
    order: int  # 탭 정렬 순서(1부터)
    video_url: str | None  # 스트리밍 URL(HTTPS). 준비중이면 null
    thumbnail_url: str | None  # 미리보기 이미지 URL. 없으면 null(현재 전부 null)
    available: bool  # 재생 가능 여부. false면 앱은 "준비중" 표시(탭은 숨기지 않음)


# -------------------------------------------------------------------------------------
# [응답] GET /exercise-videos 전체. 단계 항목을 order 순서대로 "videos" 배열로 감쌉니다.
#   예: { "videos": [ {warmup...}, {seated...}, {standing...}, {cooldown...} ] }
# -------------------------------------------------------------------------------------
class ExerciseVideosResponse(BaseModel):
    videos: list[ExerciseVideoItem]

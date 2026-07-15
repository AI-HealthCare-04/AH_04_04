# =====================================================================================
# Service(서비스) 파일입니다.
# 실제 '비즈니스 규칙'을 담당합니다. 운동 영상은:
#   - 정적 카탈로그를 order 순서로 정렬해 화면용 항목으로 변환한다.
#   - filename 유무로 available/video_url을 결정한다(서버 업로드 = 재생 가능).
#
# 콘텐츠는 정적 카탈로그(app/core/exercise_videos_catalog.py)에서 오고, 사용자별 상태가 없어
# DB(Repository) 접근이 없다. → 세션을 받지 않는 순수 로직이다.
# =====================================================================================

from app.core import config
from app.core.exercise_videos_catalog import EXERCISE_VIDEOS_CATALOG, ExerciseVideoSpec
from app.dtos.exercise_video import ExerciseVideoItem, ExerciseVideosResponse


class ExerciseVideoService:
    # [GET /exercise-videos] 운동 4단계 영상 목록을 order 순으로 돌려준다.
    def get_videos(self) -> ExerciseVideosResponse:
        ordered = sorted(EXERCISE_VIDEOS_CATALOG, key=lambda spec: spec.order)
        return ExerciseVideosResponse(videos=[self._to_item(spec) for spec in ordered])

    # 카탈로그 항목 → 응답 항목. filename이 있으면(서버 업로드됨) 재생 가능 + URL 조립, 없으면 준비중(null).
    @staticmethod
    def _to_item(spec: ExerciseVideoSpec) -> ExerciseVideoItem:
        base = config.EXERCISE_VIDEO_BASE_URL.rstrip("/")
        available = spec.filename is not None
        video_url = f"{base}/videos/{spec.filename}" if available else None
        thumbnail_url = f"{base}/videos/thumb/{spec.thumbnail_filename}" if spec.thumbnail_filename else None
        return ExerciseVideoItem(
            stage=spec.stage,
            label=spec.label,
            order=spec.order,
            video_url=video_url,
            thumbnail_url=thumbnail_url,
            available=available,
        )

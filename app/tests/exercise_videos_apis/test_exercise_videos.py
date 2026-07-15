# =====================================================================================
# 운동 영상(GET /exercise-videos) 계약 테스트.
# - 인증 요구(401)는 client 픽스처로 확인.
# - 카탈로그 형태·정렬·available/video_url 불변식은 DB 없이 순수 로직으로 검증.
# =====================================================================================

from dataclasses import replace

from httpx import AsyncClient
from starlette import status

from app.core.exercise_videos_catalog import EXERCISE_VIDEOS_CATALOG
from app.services.exercise_video import ExerciseVideoService

_EXPECTED_STAGES = {"warmup", "seated", "standing", "cooldown"}


# GET /exercise-videos 는 로그인이 필요합니다. 토큰 없이 부르면 401이 나야 합니다.
async def test_get_exercise_videos_requires_auth(client: AsyncClient) -> None:
    response = await client.get("/api/v1/exercise-videos")
    assert response.status_code == status.HTTP_401_UNAUTHORIZED
    assert "error_detail" in response.json()  # 공통 예외 핸들러 규격


# 카탈로그가 운동 4단계를 유일한 order로 갖추는지 검증합니다. (DB 불필요)
def test_catalog_has_four_stages_with_unique_order() -> None:
    stages = {spec.stage for spec in EXERCISE_VIDEOS_CATALOG}
    assert stages == _EXPECTED_STAGES
    orders = [spec.order for spec in EXERCISE_VIDEOS_CATALOG]
    assert sorted(orders) == [1, 2, 3, 4]
    for spec in EXERCISE_VIDEOS_CATALOG:
        assert spec.label  # 화면에 뿌릴 라벨은 항상 채워져 있어야 함


# 응답은 order 순으로 정렬돼 나옵니다.
def test_videos_returned_in_order() -> None:
    response = ExerciseVideoService().get_videos()
    orders = [item.order for item in response.videos]
    assert orders == sorted(orders)
    assert [item.stage for item in response.videos] == ["warmup", "seated", "standing", "cooldown"]


# 불변식: available == (video_url is not None). 준비중이면 url·thumbnail이 null이어야 한다.
def test_available_matches_video_url_presence() -> None:
    for item in ExerciseVideoService().get_videos().videos:
        assert item.available == (item.video_url is not None)
        if not item.available:
            assert item.video_url is None
            assert item.thumbnail_url is None


# 현황(서버 미업로드): 4단계 모두 준비중(available=false)이어야 한다.
def test_all_stages_currently_pending() -> None:
    videos = ExerciseVideoService().get_videos().videos
    assert all(not item.available for item in videos)
    assert all(item.video_url is None for item in videos)


# 매핑 로직: filename이 채워지면(서버 업로드) available=true + video_url이 base로 조립된다.
def test_uploaded_filename_produces_available_video_url() -> None:
    spec = replace(EXERCISE_VIDEOS_CATALOG[2], filename="standing_v1.mp4")  # standing
    item = ExerciseVideoService._to_item(spec)
    assert item.available is True
    assert item.video_url is not None
    assert item.video_url.endswith("/videos/standing_v1.mp4")

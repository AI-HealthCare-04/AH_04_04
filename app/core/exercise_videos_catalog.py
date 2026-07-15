# =====================================================================================
# 운동 영상 정적 카탈로그(Static Catalog) 파일입니다.
#
# "운동하기" 미션은 백엔드에선 단일 미션이고, 앱 UI는 4단계(몸풀기·앉아서·서서·마무리) 탭으로
# 나뉘어 각 단계마다 따라하는 영상 1개를 재생합니다. 이 영상들의 목록(단계·라벨·순서·URL)을
# 사용자별로 달라지지 않는 정적 콘텐츠로 보고, terms/데일리팁과 동일하게 카탈로그로 제공합니다.
#   - GET /exercise-videos 응답의 출처.
#   - 영상은 앱이 아니라 외부 정적 호스트(EC2 nginx, HTTPS·Range 지원)에서 스트리밍 → 앱이 캐시.
#
# available(재생 가능) 규칙: `filename`이 있으면(=서버에 실제 업로드됨) available=true + video_url 세팅,
#   없으면 available=false + video_url=null("준비중"). 영상이 서버에 올라오면 이 파일의 filename만
#   채우면 그 단계가 켜진다(앱 코드 변경 없음). 실제 URL은 config.EXERCISE_VIDEO_BASE_URL로 조립.
#
# 현황(2026-07): 4단계 모두 서버 미업로드라 available=false(준비중).
#   - warmup(몸풀기)/cooldown(마무리): 제작 예정.
#   - seated(앉아서): "To be continued" — 자리만 두고 당분간 준비중 유지.
#   - standing(서서): 로컬 초안만 있고 서버 미업로드 → 업로드 후 filename 채우면 켜짐.
# =====================================================================================

# frozen=True dataclass: 한 번 만들면 값을 못 바꾸는(불변) 데이터 묶음. 상수 카탈로그에 적합합니다.
from dataclasses import dataclass


@dataclass(frozen=True)
class ExerciseVideoSpec:
    """운동 영상 한 단계의 정적 정의. (영상 파일 자체가 아니라 '어느 단계에 어떤 영상'인지의 메타)"""

    stage: str  # 단계 키(앱이 번들 폴백 매핑에도 쓰는 안정적 식별자): warmup|seated|standing|cooldown
    label: str  # 화면 표시 라벨(어르신용 문구)
    order: int  # 탭 정렬 순서(1부터)
    filename: str | None = None  # 서버에 올라간 영상 파일명(버전 포함). 없으면 준비중(available=false)
    thumbnail_filename: str | None = None  # 미리보기 이미지 파일명. 없으면 null(앱은 후속에 표시)


# 운동 4단계. order 순서대로 앱 탭에 노출된다. filename은 서버 업로드 시 채운다(현재 전부 준비중).
EXERCISE_VIDEOS_CATALOG: tuple[ExerciseVideoSpec, ...] = (
    ExerciseVideoSpec(stage="warmup", label="몸풀기", order=1),
    ExerciseVideoSpec(stage="seated", label="앉아서 운동", order=2),
    ExerciseVideoSpec(stage="standing", label="서서 운동", order=3),
    ExerciseVideoSpec(stage="cooldown", label="마무리", order=4),
)

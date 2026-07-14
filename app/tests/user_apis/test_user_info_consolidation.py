# =====================================================================================
# `_14 내 정보` 통합 응답(GET/PATCH /users/me) 단위 테스트 — GAP #5.
# 분산돼 있던 birth_date·성별·보유포인트·운동강도 단계를 한 응답으로 모으는 조립 로직을,
# fake 레포지토리를 주입해 DB 없이 검증한다(risk 서비스 테스트와 동일 방식).
# =====================================================================================

from datetime import date, datetime
from types import SimpleNamespace

from app.dtos.users import UserInfoResponse
from app.models.enums import ActivityLevel, AuthProvider, OnboardingStatus, Sex
from app.services.users import UserManageService


def _service_with_fakes(
    *,
    profile: object | None,
    activity_profile: object | None,
    current_points: int,
) -> UserManageService:
    async def get_latest_profile(user_id: int) -> object | None:
        return profile

    async def get_by_user_id(user_id: int) -> object | None:
        return activity_profile

    async def get_current_points(user_id: int) -> int:
        return current_points

    service = UserManageService(session=None)  # type: ignore[arg-type]
    service.health_repo = SimpleNamespace(get_latest_profile=get_latest_profile)  # type: ignore[assignment]
    service.activity_repo = SimpleNamespace(get_by_user_id=get_by_user_id)  # type: ignore[assignment]
    service.dashboard_repo = SimpleNamespace(get_current_points=get_current_points)  # type: ignore[assignment]
    return service


def _user() -> SimpleNamespace:
    return SimpleNamespace(
        user_id=1,
        provider=AuthProvider.GUEST,
        nickname="홍길동",
        onboarding_status=OnboardingStatus.COMPLETED,
        created_at=datetime(2026, 7, 1, 9, 0, 0),
    )


# 세 소스가 모두 있으면 계정 정보 + birth_date·성별·포인트·운동강도가 한 응답에 모인다.
async def test_get_user_info_consolidates_all_sources() -> None:
    profile = SimpleNamespace(birth_date=date(1958, 3, 1), sex=Sex.MALE)
    activity_profile = SimpleNamespace(current_level=ActivityLevel.NORMAL)
    service = _service_with_fakes(profile=profile, activity_profile=activity_profile, current_points=1250)

    info = await service.get_user_info(_user())  # type: ignore[arg-type]

    assert isinstance(info, UserInfoResponse)
    assert info.user_id == 1
    assert info.nickname == "홍길동"
    assert info.onboarding_status == "completed"
    assert info.birth_date == date(1958, 3, 1)
    assert info.sex == "male"
    assert info.current_points == 1250
    assert info.activity_level == ActivityLevel.NORMAL


# 건강프로필/활동프로필이 아직 없고 적립도 없으면: birth_date/sex=null, 포인트=0, 강도=easy(홈과 동일 기본값).
async def test_get_user_info_uses_safe_defaults_when_sources_missing() -> None:
    service = _service_with_fakes(profile=None, activity_profile=None, current_points=0)

    info = await service.get_user_info(_user())  # type: ignore[arg-type]

    assert info.birth_date is None
    assert info.sex is None
    assert info.current_points == 0
    assert info.activity_level == ActivityLevel.EASY


# 응답 스키마가 통합 필드를 실제로 포함하는지 잠근다(계약 회귀 방지).
def test_user_info_response_shape_includes_consolidated_fields() -> None:
    fields = set(UserInfoResponse.model_fields.keys())
    assert {"birth_date", "sex", "current_points", "activity_level"} <= fields
    # 기존 계정 필드도 유지된다(하위호환).
    assert {"user_id", "provider", "nickname", "onboarding_status", "created_at"} <= fields

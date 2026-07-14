from datetime import date

from pydantic import BaseModel, Field

from app.dtos.base import BaseSerializerModel, KstDatetime
from app.models.enums import ActivityLevel, FontSize, SoundSize


# [응답] 내 정보 (GET/PATCH /users/me) — 화면 `_14 내 정보`용 통합 응답.
#   기존 계정 정보(user_id·provider·nickname·onboarding_status·가입일)에 더해,
#   그동안 분산돼 있던 birth_date·성별·보유포인트·운동강도 단계를 한 응답으로 모은다(GAP #5).
#     - birth_date/sex : 최신 건강프로필(사용자 입력분)에서. 아직 건강체크 전이면 null.
#     - current_points : 미션 적립 합계(보유 포인트).
#     - activity_level : 운동 강도 단계(easy/normal/hard). 프로필이 없으면 easy(홈 표시와 동일 기본값).
class UserInfoResponse(BaseSerializerModel):
    user_id: int
    provider: str
    nickname: str
    onboarding_status: str
    created_at: KstDatetime
    birth_date: date | None = None  # 생년월일(YYYY-MM-DD). 건강프로필 없으면 null
    sex: str | None = None  # 성별("male"/"female"). 건강프로필 없으면 null
    current_points: int = 0  # 보유 포인트(미션 적립 합)
    activity_level: ActivityLevel = ActivityLevel.EASY  # 운동 강도 단계(프로필 없으면 easy)


class UserUpdateRequest(BaseModel):
    nickname: str | None = Field(default=None, min_length=1, max_length=50)


# [요청] 회원탈퇴 (DELETE /users/me). confirm=true여야 진행하고, 아니면 400으로 거부한다.
class UserWithdrawRequest(BaseModel):
    confirm: bool


# [응답] 설정 조회/변경 공통 응답. 항상 전체 설정을 반환한다.
# 기본값은 personalized_settings 모델 기본값과 동일하게 맞춘다(font/sound=medium, pet_type="default").
# font/sound는 DB enum(small/medium/large)과 동일하게 타입을 좁혀 계약을 보호한다.
# notification_enabled는 알림 기능 미구현으로 계약에서 제외한다(v7.8).
class UserSettingsResponse(BaseModel):
    font_size: FontSize = FontSize.MEDIUM
    sound_size: SoundSize = SoundSize.MEDIUM
    pet_type: str = "default"
    music_enabled: bool = True


# [응답] 고객센터 정보 (명세 §12). 문의용 이메일 1개.
class SupportResponse(BaseModel):
    email: str


# [요청] 설정 부분 변경 (명세 §10). 보낸 필드만 변경한다.
# font/sound는 enum으로 좁혀 잘못된 값("huge" 등)을 422로 거부한다.
# None은 "변경 안 함"으로 취급되어 서비스에서 exclude_none으로 무시된다(명시적 null → no-op).
class UserSettingsUpdateRequest(BaseModel):
    font_size: FontSize | None = None
    sound_size: SoundSize | None = None
    pet_type: str | None = None
    music_enabled: bool | None = None

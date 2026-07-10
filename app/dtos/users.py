from datetime import datetime

from pydantic import BaseModel, Field

from app.dtos.base import BaseSerializerModel
from app.models.enums import FontSize, SoundSize


class UserInfoResponse(BaseSerializerModel):
    user_id: int
    provider: str
    nickname: str
    onboarding_status: str
    created_at: datetime


class UserUpdateRequest(BaseModel):
    nickname: str | None = Field(default=None, min_length=1, max_length=50)


# [요청] 회원탈퇴 (DELETE /users/me). confirm=true여야 진행하고, 아니면 400으로 거부한다.
class UserWithdrawRequest(BaseModel):
    confirm: bool


# [응답] 설정 조회/변경 공통 응답 (명세 §8/§10). 항상 전체 설정(5필드)을 반환한다.
# 기본값은 personalized_settings 모델 기본값과 동일하게 맞춘다(font/sound=medium, pet_type="default").
# font/sound는 DB enum(small/medium/large)과 동일하게 타입을 좁혀 v7.2 계약을 보호한다.
class UserSettingsResponse(BaseModel):
    notification_enabled: bool = True
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
    notification_enabled: bool | None = None
    font_size: FontSize | None = None
    sound_size: SoundSize | None = None
    pet_type: str | None = None
    music_enabled: bool | None = None

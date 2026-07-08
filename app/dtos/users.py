from datetime import datetime

from pydantic import BaseModel, Field

from app.dtos.base import BaseSerializerModel


class UserInfoResponse(BaseSerializerModel):
    user_id: int
    provider: str
    nickname: str
    onboarding_status: str
    created_at: datetime


class UserUpdateRequest(BaseModel):
    nickname: str | None = Field(default=None, min_length=1, max_length=50)


# [응답] 설정 조회/변경 공통 응답 (명세 §8/§10). 항상 전체 설정(5필드)을 반환한다.
# 기본값은 personalized_settings 모델 기본값과 동일하게 맞춘다(font/sound=medium, pet_type="default").
class UserSettingsResponse(BaseModel):
    notification_enabled: bool = True
    font_size: str = "medium"
    sound_size: str = "medium"
    pet_type: str = "default"
    music_enabled: bool = True


# [요청] 설정 부분 변경 (명세 §10). 보낸 필드만 변경한다.
class UserSettingsUpdateRequest(BaseModel):
    notification_enabled: bool | None = None
    font_size: str | None = None
    sound_size: str | None = None
    pet_type: str | None = None
    music_enabled: bool | None = None

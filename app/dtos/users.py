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


class UserSettingsResponse(BaseModel):
    notification_enabled: bool = True
    font_size: str = "default"
    sound_size: str = "default"
    music_enabled: bool = True


class UserSettingsUpdateRequest(BaseModel):
    notification_enabled: bool | None = None
    font_size: str | None = None
    sound_size: str | None = None
    music_enabled: bool | None = None

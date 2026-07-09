from datetime import datetime

from pydantic import BaseModel

from app.dtos.base import BaseSerializerModel
from app.models.enums import ActivityLevel, LevelReason


class ActivityProfileResponse(BaseSerializerModel):
    activity_profile_id: int
    current_level: ActivityLevel
    level_reason: LevelReason
    physical_assessment_id: int | None
    started_at: datetime
    updated_at: datetime


# PATCH /users/me/activity-profile
# 사용자가 난이도 변경을 명시적으로 수락했을 때만 현재 레벨을 바꾼다.
# activity_level_change_logs는 실제 난이도 변경 이력 API를 확장할 때 별도 PR에서 추가한다.
class ActivityProfileUpdateRequest(BaseModel):
    to_level: ActivityLevel
    reason_type: LevelReason = LevelReason.USER_SELECTED
    accepted_by_user: bool = True

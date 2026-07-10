from datetime import datetime

from pydantic import BaseModel

from app.dtos.base import BaseSerializerModel
from app.models.enums import ActivityLevel, LevelReason, ReasonType


class ActivityProfileResponse(BaseSerializerModel):
    activity_profile_id: int
    current_level: ActivityLevel
    level_reason: LevelReason
    physical_assessment_id: int | None
    started_at: datetime
    updated_at: datetime


# PATCH /users/me/activity-profile 활동 난이도 변경 요청.
# 사용자가 명시적으로 수락한 경우에만 현재 난이도를 바꾸고,
# 요청 reason_type은 activity_level_change_logs 이력에 저장한다.
class ActivityProfileUpdateRequest(BaseModel):
    to_level: ActivityLevel
    reason_type: ReasonType
    accepted_by_user: bool

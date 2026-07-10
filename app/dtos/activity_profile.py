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


# PATCH /users/me/activity-profile — 운동 난이도 변경.
# 사용자가 난이도 변경을 명시적으로 수락했을 때만 현재 레벨을 바꾼다.
# 요청 reason_type(변경 사유)은 저장되는 level_reason(상태 사유)과 분리한다:
#   사용자 수락 변경의 결과 level_reason은 항상 user_selected로 저장하고,
#   요청 reason_type 이력은 후속 activity_level_change_logs 테이블에 남긴다.
class ActivityProfileUpdateRequest(BaseModel):
    to_level: ActivityLevel
    reason_type: ReasonType
    accepted_by_user: bool

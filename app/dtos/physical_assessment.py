import logging
from datetime import datetime
from decimal import Decimal
from typing import Any

from pydantic import BaseModel, Field, model_validator

from app.models.enums import ActivityLevel, AssessmentType, LevelReason

_logger = logging.getLogger(__name__)

# 6m 걷기는 미구현·제외(#109). 아래 필드는 'deprecated no-op'이다:
#   구버전 앱이 계속 보낼 수 있어 422로 막지 않고 무시한다(밴드는 5STS 단독이라 결과 동일).
#   일반적인 unknown 필드와 달리 '의도적으로 폐기된 계약'임을 명시하고, 수신 시 로그를 남겨
#   #115(앱 6m 제거) 배포 후 이 no-op 처리를 걷어낼 시점을 판단한다(리뷰 #118-3).
_DEPRECATED_WALK_6M_KEYS = (
    "walk_6m_time_sec",
    "walk_6m_distance_m",
    "walk_6m_speed_mps",
    "walk_6m_skipped",
)


class PhysicalAssessmentCreateRequest(BaseModel):
    session_id: int | None = None
    assessment_type: AssessmentType = AssessmentType.INITIAL
    # le=600(약 10분): 근본 방어(#298 A-2). 5STS 는 임상적으로 수 초~수십 초지만 측정 방치 시 값이 무한 증가할 수
    #   있어, DB Numeric(5,2)=999.99 초과 DataError 500 이전에 422 로 거른다. 정상 측정은 막지 않는다.
    chair_stand_5_time_sec: Decimal | None = Field(default=None, gt=0, le=600)
    chair_stand_skipped: bool = False
    pain_reported: bool = False
    dizziness_reported: bool = False

    @model_validator(mode="before")
    @classmethod
    def _ignore_deprecated_walk_6m(cls, data: Any) -> Any:
        # deprecated no-op: walk_6m_* 수신을 로그로 남기고 조용히 무시한다(extra=ignore가 실제 드롭).
        if isinstance(data, dict):
            present = [key for key in _DEPRECATED_WALK_6M_KEYS if key in data]
            if present:
                _logger.info("deprecated walk_6m fields received and ignored (#109): %s", present)
        return data

    @model_validator(mode="after")
    def validate_measurements(self) -> "PhysicalAssessmentCreateRequest":
        # 밴드 산출 입력인 5STS(chair_stand)는 필수(스킵 아니면). 6m 걷기는 미구현·제외(#109).
        if not self.chair_stand_skipped and self.chair_stand_5_time_sec is None:
            raise ValueError("chair_stand_5_time_sec is required unless chair_stand_skipped is true.")
        if self.chair_stand_skipped and self.chair_stand_5_time_sec is not None:
            raise ValueError("chair_stand_5_time_sec must be omitted when chair_stand_skipped is true.")
        return self


class PhysicalAssessmentActivityProfile(BaseModel):
    current_level: ActivityLevel
    level_reason: LevelReason


class PhysicalAssessmentHistoryItem(BaseModel):
    """5STS 측정 이력 항목(#353). 측정 기록(시간 존재)만 담는다 — 스킵 기록은 추이에 안 쓴다.

    비의료 가드레일(#57): 시간·시각 등 사실만 내려주고 판정(저하 등)은 포함하지 않는다.
    """

    physical_assessment_id: int
    assessment_type: AssessmentType
    chair_stand_5_time_sec: float
    created_at: datetime


class PhysicalAssessmentHistoryResponse(BaseModel):
    assessments: list[PhysicalAssessmentHistoryItem]


class PhysicalAssessmentResponse(BaseModel):
    physical_assessment_id: int
    used_for_level_setting: bool
    activity_profile: PhysicalAssessmentActivityProfile

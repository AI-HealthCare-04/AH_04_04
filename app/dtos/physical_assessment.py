from pydantic import BaseModel


class PhysicalAssessmentCreateRequest(BaseModel):
    assessment_type: str
    chair_stand_5_time_sec: float | None = None
    chair_stand_skipped: bool = False
    walk_6m_time_sec: float | None = None
    walk_6m_distance_m: float | None = None
    walk_6m_skipped: bool = False

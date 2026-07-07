from datetime import date

from pydantic import BaseModel


class HealthProfileCreateRequest(BaseModel):
    birth_date: date
    sex: str
    height_cm: float
    weight_kg: float
    waist_cm: float | None = None
    walking_practice: bool
    strength_exercise: bool
    kidney_status: str = "unknown"
    protein_restriction_status: str = "unknown"

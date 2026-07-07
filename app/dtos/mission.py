from pydantic import BaseModel


class MissionResponse(BaseModel):
    mission_template_id: int
    mission_type: str
    title: str
    level: str
    reward_points: int

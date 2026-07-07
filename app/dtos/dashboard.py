from pydantic import BaseModel


class PointBalanceResponse(BaseModel):
    current_points: int

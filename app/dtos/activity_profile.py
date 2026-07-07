from pydantic import BaseModel


class ActivityProfileUpdateRequest(BaseModel):
    to_level: str
    reason_type: str
    accepted_by_user: bool = True

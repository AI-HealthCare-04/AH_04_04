from pydantic import BaseModel


class TermResponse(BaseModel):
    terms_type: str
    version: str
    is_required: bool

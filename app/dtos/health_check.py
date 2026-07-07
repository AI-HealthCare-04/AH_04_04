from pydantic import BaseModel


class HealthCheckSessionCreateRequest(BaseModel):
    input_method: str = "form"

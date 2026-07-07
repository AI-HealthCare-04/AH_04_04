from pydantic import BaseModel, Field


class GoogleLoginRequest(BaseModel):
    authorization_code: str = Field(..., description="Google OAuth authorization code from Android client")


class LoginResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    onboarding_status: str


class LogoutResponse(BaseModel):
    detail: str = "logged out"

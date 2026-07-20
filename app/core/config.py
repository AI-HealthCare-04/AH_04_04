import os
import uuid
import zoneinfo
from enum import StrEnum
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Env(StrEnum):
    LOCAL = "local"
    DEV = "dev"
    PROD = "prod"


class Config(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="allow")

    ENV: Env = Env.LOCAL
    SECRET_KEY: str = f"default-secret-key{uuid.uuid4().hex}"
    TIMEZONE: zoneinfo.ZoneInfo = Field(default_factory=lambda: zoneinfo.ZoneInfo("Asia/Seoul"))
    TEMPLATE_DIR: str = os.path.join(Path(__file__).resolve().parent.parent, "templates")

    DB_HOST: str = "localhost"
    DB_PORT: int = 3306
    DB_USER: str = "root"
    DB_PASSWORD: str = "pw1234"
    DB_NAME: str = "ai_health"
    DB_CONNECT_TIMEOUT: int = 5
    DB_CONNECTION_POOL_MAXSIZE: int = 10

    COOKIE_DOMAIN: str = "localhost"

    # 고객센터 문의 이메일(명세 §12). 실제 값은 배포 환경변수 SUPPORT_EMAIL로 주입한다.
    SUPPORT_EMAIL: str = "support@example.com"

    # 약관 전문 링크(GET /terms). 실제 문서 확정 시 배포 환경변수로 교체한다(현재는 placeholder).
    TERMS_SERVICE_URL: str = "https://example.com/terms/service-1.0"
    TERMS_PRIVACY_URL: str = "https://example.com/terms/privacy-1.0"
    TERMS_SENSITIVE_HEALTH_URL: str = "https://example.com/terms/sensitive-health-1.0"
    TERMS_MARKETING_URL: str = "https://example.com/terms/marketing-1.0"

    # 운동 영상(GET /exercise-videos) 정적 호스트(EC2 nginx) 루트. 실제 배포 시 환경변수로 교체.
    #   video_url = f"{EXERCISE_VIDEO_BASE_URL}/videos/{파일명}" 로 조립(현재는 placeholder).
    EXERCISE_VIDEO_BASE_URL: str = "https://videos.example.com"

    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 60
    REFRESH_TOKEN_EXPIRE_MINUTES: int = 14 * 24 * 60
    JWT_LEEWAY: int = 5

    # OIDC audience. Android와 동일한 Google Web Client ID / Kakao Native App Key를 주입한다.
    # 비어 있으면 해당 공급자 로그인은 503으로 닫힌다. 비밀값은 아니지만 환경별로 관리한다.
    GOOGLE_CLIENT_ID: str = ""
    KAKAO_NATIVE_APP_KEY: str = ""
    # 기존 authorization-code 배포 설정과의 호환을 위해 남긴 deprecated 항목.
    GOOGLE_CLIENT_SECRET: str = ""
    GOOGLE_REDIRECT_URI: str = ""
    KAKAO_CLIENT_ID: str = ""  # 카카오 REST API 키
    KAKAO_CLIENT_SECRET: str = ""  # 카카오는 선택(보안 강화 옵션)
    KAKAO_REDIRECT_URI: str = ""

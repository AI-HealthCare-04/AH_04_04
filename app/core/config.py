import os
import uuid
import zoneinfo
from enum import StrEnum
from pathlib import Path
from typing import Self

from pydantic import Field, model_validator
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
    DB_PASSWORD: str = ""
    # 앱 코드는 쓰지 않지만 prod compose 가 env_file(.env)로 앱 컨테이너에도 주입하는 값 —
    #   기동 시 강도 검증(fail-fast, #270)만 한다. 로컬/dev 에는 없어도 된다(PROD 에서만 검사).
    DB_ROOT_PASSWORD: str = ""
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

    @model_validator(mode="after")
    def reject_insecure_prod_secrets(self) -> Self:
        """운영 비밀값 fail-fast(#262 기본값 차단 + #270 길이·무작위성 하한).

        하한 근거(#270): SECRET_KEY 는 JWT 서명 키라 32자(256비트급). DB 비밀번호는 16자 —
        현 운영값(20자 무작위)을 수용하는 값으로, 32자 상향은 심사 후 비밀번호 로테이션과 함께 한다.
        고유 문자 종수(8종)는 'aaaa…'·반복 패턴 같은 저엔트로피 값을 거르는 가벼운 무작위성 검사다
        (무작위 16자면 항상 통과). 오류 메시지에 실제 값·실제 길이는 남기지 않는다(로그 위생).
        """
        if self.ENV is not Env.PROD:
            return self

        problems: list[str] = []
        if self.SECRET_KEY.startswith("default-secret-key") or self.SECRET_KEY == "change-me-prod-secret":
            problems.append("SECRET_KEY: 기본값/플레이스홀더는 쓸 수 없습니다")
        else:
            problems += _secret_strength_problems("SECRET_KEY", self.SECRET_KEY, min_length=32)
        if self.DB_PASSWORD in {"pw1234", "Password1234@"}:
            problems.append("DB_PASSWORD: 알려진 기본값은 쓸 수 없습니다")
        else:
            problems += _secret_strength_problems("DB_PASSWORD", self.DB_PASSWORD, min_length=16)
        problems += _secret_strength_problems("DB_ROOT_PASSWORD", self.DB_ROOT_PASSWORD, min_length=16)

        if problems:
            raise ValueError(f"운영 환경 비밀값 검증 실패: {'; '.join(problems)}")
        return self


def _secret_strength_problems(name: str, value: str, *, min_length: int, min_distinct: int = 8) -> list[str]:
    """비밀값 강도 위반 목록(#270). 비었거나 짧으면 길이 사유, 길지만 문자 다양성이 없으면 무작위성 사유.

    메시지에 값이나 실제 길이를 포함하지 않는다 — 기동 실패 로그가 수집돼도 비밀값 단서가 남지 않게.
    """
    stripped = value.strip()
    if len(stripped) < min_length:
        return [f"{name}: 최소 {min_length}자 이상의 무작위 값이 필요합니다"]
    if len(set(stripped)) < min_distinct:
        return [f"{name}: 문자 다양성이 부족합니다(반복 패턴 의심) — 무작위 생성 값을 사용하세요"]
    return []

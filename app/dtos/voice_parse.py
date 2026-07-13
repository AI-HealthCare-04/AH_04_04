from enum import StrEnum

from pydantic import BaseModel, Field, field_validator


class VoiceParseField(StrEnum):
    """음성으로 입력받을 수 있는 건강 프로필 필드(수동입력 폼과 1:1 대응)."""

    HEIGHT_CM = "height_cm"
    WEIGHT_KG = "weight_kg"
    WAIST_CM = "waist_cm"
    BIRTH_DATE = "birth_date"
    SEX = "sex"
    WALKING_PRACTICE = "walking_practice"
    STRENGTH_EXERCISE = "strength_exercise"
    KIDNEY_STATUS = "kidney_status"
    PROTEIN_RESTRICTION_STATUS = "protein_restriction_status"


class VoiceParseRequest(BaseModel):
    """앱이 알고 있는 현재 질문 필드 + 온디바이스 STT 원문을 그대로 보낸다."""

    field: VoiceParseField
    raw_transcript: str = Field(min_length=1)

    @field_validator("raw_transcript")
    @classmethod
    def strip_transcript(cls, value: str) -> str:
        stripped = value.strip()
        if not stripped:
            raise ValueError("raw_transcript must not be blank.")
        return stripped


class VoiceParseResponse(BaseModel):
    """단일 필드 파싱 결과. 파싱 실패 시 value=None → 앱은 수동입력 폼으로 폴백한다.

    확인 문구(confirm_prompt)는 서버 의존을 줄이려 앱에서 로컬로 만든다.
    """

    field: VoiceParseField
    value: bool | float | str | None
    needs_confirmation: bool

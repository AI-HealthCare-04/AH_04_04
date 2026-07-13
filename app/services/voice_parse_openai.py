"""음성 텍스트(raw_transcript)를 OpenAI(LLM)로 파싱하는 서비스.

멘토링에서 "OpenAI API로 구동될지 모르겠다"는 질문을 검증하기 위한 실험용 파서다.
규칙기반 파서(``app.services.voice_parse.VoiceParseService``)와 **응답 계약이 동일**하므로
(같은 ``VoiceParseResponse``), 앱 수정 없이 서버 파서만 규칙기반 ↔ LLM 으로 바꿔 끼울 수 있다.

- 새 의존성을 만들지 않으려고 OpenAI 공식 SDK 대신 이미 있는 ``httpx`` 로 REST 를 직접 호출한다.
- ``.env`` 의 ``OPENAI_API_KEY`` 가 있어야 동작한다(없으면 ``RuntimeError``).
- 상식 범위 밖 수치는 규칙기반과 동일하게 오인식으로 보고 ``value=None`` 처리한다.
- 생년월일은 규칙기반과 동일하게 실제 달력 날짜만 통과시킨다(불가능/형식오류 → ``value=None``).
"""
import json
from datetime import date

import httpx

from app.core import config, default_logger
from app.dtos.voice_parse import VoiceParseField, VoiceParseResponse
from app.models.enums import KidneyStatus, ProteinRestrictionStatus, Sex

# 규칙기반 파서와 동일한 상식 범위(공정 비교를 위해 값을 맞춘다).
_HEIGHT_RANGE = (100.0, 250.0)
_WEIGHT_RANGE = (20.0, 300.0)
_WAIST_RANGE = (30.0, 200.0)
_MEASUREMENT_RANGES = {
    VoiceParseField.HEIGHT_CM: _HEIGHT_RANGE,
    VoiceParseField.WEIGHT_KG: _WEIGHT_RANGE,
    VoiceParseField.WAIST_CM: _WAIST_RANGE,
}

# 필드별로 LLM 에게 요구할 출력 형태. 규칙기반 파서의 의미와 1:1로 맞춘다.
_FIELD_SPECS: dict[VoiceParseField, str] = {
    VoiceParseField.HEIGHT_CM: (
        '키를 센티미터 숫자(float)로. "일미터 육십"·"1미터 60"은 160. '
        f"상식 범위 {_HEIGHT_RANGE[0]:.0f}~{_HEIGHT_RANGE[1]:.0f}cm 밖이면 오인식이므로 null."
    ),
    VoiceParseField.WEIGHT_KG: (
        f"몸무게를 킬로그램 숫자(float)로. 범위 {_WEIGHT_RANGE[0]:.0f}~{_WEIGHT_RANGE[1]:.0f}kg 밖이면 null."
    ),
    VoiceParseField.WAIST_CM: (
        f"허리둘레를 센티미터 숫자(float)로. 범위 {_WAIST_RANGE[0]:.0f}~{_WAIST_RANGE[1]:.0f}cm 밖이면 null."
    ),
    VoiceParseField.BIRTH_DATE: (
        '생년월일을 "YYYY-MM-DD" 문자열로. 2자리 연도는 1900년대로 확장('
        '"오팔년 삼월 일일" → "1958-03-01"). 날짜로 해석 불가하면 null.'
    ),
    VoiceParseField.SEX: '성별을 "male" 또는 "female" 문자열로. 판단 불가하면 null.',
    VoiceParseField.WALKING_PRACTICE: "걷기 운동을 하는지 여부를 true/false 로. 부정("
    '"안 해요")이면 false, "몰라요"·"잘 모르겠어요"면 null.',
    VoiceParseField.STRENGTH_EXERCISE: "근력운동을 하는지 여부를 true/false 로. 부정이면 false, "
    '"몰라요"면 null.',
    VoiceParseField.KIDNEY_STATUS: (
        '신장 상태를 "none"(정상/없음)·"kidney_disease"(신장병)·"dialysis"(투석)·"unknown"(모름) 중 하나로. '
        '"신장병 없어요"·"투석 안 받아요"는 "none".'
    ),
    VoiceParseField.PROTEIN_RESTRICTION_STATUS: (
        '단백질 제한 여부를 "none"(제한 없음)·"restricted"(제한 함)·"unknown"(모름) 중 하나로.'
    ),
}

_ALLOWED_STRING_VALUES: dict[VoiceParseField, set[str]] = {
    VoiceParseField.SEX: {s.value for s in Sex},
    VoiceParseField.KIDNEY_STATUS: {s.value for s in KidneyStatus},
    VoiceParseField.PROTEIN_RESTRICTION_STATUS: {s.value for s in ProteinRestrictionStatus},
}

_SYSTEM_PROMPT = (
    "너는 한국 노인 대상 건강 온보딩의 음성 입력을 파싱하는 도우미다. "
    "온디바이스 STT 가 넘긴 한국어 원문에서 요청한 필드 값 하나만 뽑는다. "
    "확실하지 않거나 모호하면 반드시 null 을 반환한다(추측 금지). "
    '반드시 {"value": <값 또는 null>} 형태의 JSON 하나만 출력한다.'
)


def _build_user_prompt(field: VoiceParseField, raw_transcript: str) -> str:
    spec = _FIELD_SPECS[field]
    return (
        f"필드: {field.value}\n"
        f"규칙: {spec}\n"
        f"음성 원문: {raw_transcript!r}\n"
        '위 규칙으로 값을 뽑아 {"value": ...} JSON 으로만 답하라.'
    )


def _coerce(field: VoiceParseField, value: object) -> bool | float | str | None:
    """LLM 이 준 raw value 를 계약 타입으로 정리. 계약을 벗어나면 None(폼 폴백)."""
    if value is None:
        return None

    if field in _MEASUREMENT_RANGES:
        if not isinstance(value, (int, float, str)) or isinstance(value, bool):
            return None
        try:
            num = float(value)  # "160" 같은 문자열 숫자도 허용
        except ValueError:
            return None
        low, high = _MEASUREMENT_RANGES[field]
        return num if low <= num <= high else None

    if field in (VoiceParseField.WALKING_PRACTICE, VoiceParseField.STRENGTH_EXERCISE):
        return value if isinstance(value, bool) else None

    if field is VoiceParseField.BIRTH_DATE:
        if not isinstance(value, str):
            return None
        try:
            # 규칙기반 파서와 동일하게 실제 달력 날짜만 인정한다. "1958-13-40"(불가능한 날짜)이나
            # "1958/03/01"(형식 위반)은 ValueError → None 으로 폼 폴백. 통과 시 정규 ISO(YYYY-MM-DD)로 반환.
            return date.fromisoformat(value).isoformat()
        except ValueError:
            return None

    allowed = _ALLOWED_STRING_VALUES.get(field)
    if allowed is not None:
        return value if isinstance(value, str) and value in allowed else None

    return None


class OpenAIVoiceParseService:
    """OpenAI(LLM) 기반 파서. 규칙기반 ``VoiceParseService`` 와 동일한 응답을 낸다."""

    @staticmethod
    async def parse(field: VoiceParseField, raw_transcript: str) -> VoiceParseResponse:
        raw_value = await OpenAIVoiceParseService._call_openai(field, raw_transcript)
        value = _coerce(field, raw_value)
        return VoiceParseResponse(field=field, value=value, needs_confirmation=value is not None)

    @staticmethod
    async def _call_openai(field: VoiceParseField, raw_transcript: str) -> object:
        if not config.OPENAI_API_KEY:
            raise RuntimeError("OPENAI_API_KEY 가 설정되지 않았습니다(.env 확인).")

        payload = {
            "model": config.OPENAI_MODEL,
            "temperature": 0,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": _SYSTEM_PROMPT},
                {"role": "user", "content": _build_user_prompt(field, raw_transcript)},
            ],
        }
        headers = {"Authorization": f"Bearer {config.OPENAI_API_KEY}"}

        # OpenAI 4xx/5xx·429·타임아웃·네트워크 오류, 응답 구조 이상, JSON 파싱 실패는 모두
        # STT 실패로 간주해 None 을 돌려준다 → _coerce 를 거쳐 value=None(needs_confirmation=False)
        # → 앱 수동입력 폼 폴백. 팀 원칙("STT 실패 시 진행 차단 금지")에 맞춰 예외를 밖으로 올리지 않는다.
        # (JSONDecodeError 는 ValueError 의 하위라 아래 ValueError 로 함께 잡힌다.)
        try:
            async with httpx.AsyncClient(timeout=config.OPENAI_TIMEOUT) as client:
                response = await client.post(
                    f"{config.OPENAI_BASE_URL}/chat/completions",
                    json=payload,
                    headers=headers,
                )
                response.raise_for_status()
                data = response.json()
            content = data["choices"][0]["message"]["content"]
            return json.loads(content).get("value")
        except (httpx.HTTPError, KeyError, IndexError, TypeError, ValueError) as exc:
            default_logger.warning("OpenAI voice parse 실패(field=%s): %s", field.value, exc)
            return None

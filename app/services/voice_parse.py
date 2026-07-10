"""음성 텍스트(raw_transcript)에서 필드 값 하나를 규칙기반으로 뽑아내는 stateless 파서.

방침(팀 확정):
- STT(음성→텍스트)는 앱(온디바이스)에서 하고, 서버는 텍스트만 받아 파싱만 한다.
- 수동입력 폼이 항상 우선이며, 파싱 실패(value=None)는 에러가 아니라 폼 폴백 신호다.
- LLM은 붙이지 않는다. 인식 품질이 부족하면 이후 동일 응답 계약으로 LLM 파서로 교체한다.
"""
import re
from datetime import date

from app.dtos.voice_parse import VoiceParseField, VoiceParseResponse
from app.models.enums import KidneyStatus, ProteinRestrictionStatus, Sex

# 한국어 수사(한자어) → 값
_SINO_DIGITS = {
    "영": 0, "공": 0, "일": 1, "이": 2, "삼": 3, "사": 4,
    "오": 5, "육": 6, "륙": 6, "칠": 7, "팔": 8, "구": 9,
}
_SINO_UNITS = {"십": 10, "백": 100, "천": 1000}

# 노인 자기보고 기준의 상식 범위 — 벗어난 값은 오인식으로 보고 폼 폴백시킨다.
_HEIGHT_RANGE = (100.0, 250.0)
_WEIGHT_RANGE = (20.0, 300.0)
_WAIST_RANGE = (30.0, 200.0)

# 예/아니오/모름 판정(순서 중요: 모름 → 아니오 → 예)
_UNKNOWN_HINTS = ("몰라", "모르", "글쎄", "기억")
_NO_HINTS = ("아니", "아뇨", "안해", "안함", "없어", "없다", "못해", "안 ")
_YES_HINTS = ("네", "예", "응", "그렇", "맞", "해요", "한다", "있어", "있다", "가끔", "자주")


def _korean_to_number(text: str) -> float | None:
    """아라비아 숫자 또는 한국어 수사를 float으로. 실패 시 None."""
    text = text.strip()
    if not text:
        return None
    arabic = re.search(r"\d+(?:\.\d+)?", text)
    if arabic:
        return float(arabic.group())
    if any(unit in text for unit in _SINO_UNITS):
        return _sino_positional(text)
    # 자릿값 없이 읽은 숫자열(예: "오팔" → 58, "삼" → 3)
    digits = [_SINO_DIGITS[ch] for ch in text if ch in _SINO_DIGITS]
    if not digits:
        return None
    return float(int("".join(str(digit) for digit in digits)))


def _sino_positional(text: str) -> float | None:
    """자릿값이 있는 한자어 수사(예: 백육십 → 160, 오십팔 → 58)."""
    total = 0
    current = 0
    for ch in text:
        if ch in _SINO_DIGITS:
            current = _SINO_DIGITS[ch]
        elif ch in _SINO_UNITS:
            total += (current or 1) * _SINO_UNITS[ch]
            current = 0
        else:
            return None
    return float(total + current)


def _within(value: float | None, bounds: tuple[float, float]) -> float | None:
    if value is None:
        return None
    low, high = bounds
    return value if low <= value <= high else None


def _expand_two_digit_year(year: int) -> int:
    """2자리 연도 확장. 노인 대상 서비스라 1900년대로 본다(예: 58 → 1958)."""
    return 1900 + year


def _parse_height(transcript: str) -> float | None:
    text = transcript.replace(" ", "").lower()
    for token in ("센티미터", "센치미터", "센티", "센치", "cm"):
        text = text.replace(token, "")
    text = text.replace("미터", "m")
    if "m" in text:
        meter_part, _, cm_part = text.partition("m")
        meters = 1.0 if meter_part == "" else _korean_to_number(meter_part)
        if meters is None:
            return None
        centimeters = _korean_to_number(cm_part) or 0.0
        return _within(meters * 100 + centimeters, _HEIGHT_RANGE)
    return _within(_korean_to_number(text), _HEIGHT_RANGE)


def _parse_weight(transcript: str) -> float | None:
    text = transcript.replace(" ", "").lower()
    for token in ("킬로그램", "키로그램", "킬로", "키로", "kg"):
        text = text.replace(token, "")
    return _within(_korean_to_number(text), _WEIGHT_RANGE)


def _parse_waist(transcript: str) -> float | None:
    text = transcript.replace(" ", "").lower()
    for token in ("센티미터", "센치미터", "센티", "센치", "cm"):
        text = text.replace(token, "")
    return _within(_korean_to_number(text), _WAIST_RANGE)


def _parse_birth_date(transcript: str) -> str | None:
    text = transcript.replace(" ", "")
    year_token, has_year, rest = text.partition("년")
    if not has_year:
        return None
    month_token, has_month, day_rest = rest.partition("월")
    if not has_month:
        return None
    day_token, _, _ = day_rest.rpartition("일")  # 일(day) 단위는 맨 끝 → rpartition
    year = _korean_to_number(year_token)
    month = _korean_to_number(month_token)
    day = _korean_to_number(day_token)
    if year is None or month is None or day is None:
        return None
    year_int = int(year)
    if year_int < 100:
        year_int = _expand_two_digit_year(year_int)
    try:
        return date(year_int, int(month), int(day)).isoformat()
    except ValueError:
        return None


def _parse_sex(transcript: str) -> str | None:
    text = transcript.replace(" ", "")
    if any(hint in text for hint in ("남자", "남성", "남")):
        return Sex.MALE.value
    if any(hint in text for hint in ("여자", "여성", "여")):
        return Sex.FEMALE.value
    return None


def _parse_bool(transcript: str) -> bool | None:
    text = transcript.replace(" ", "")
    if any(hint in text for hint in _UNKNOWN_HINTS):
        return None
    if any(hint in text for hint in _NO_HINTS):
        return False
    if any(hint in text for hint in _YES_HINTS):
        return True
    return None


def _parse_kidney_status(transcript: str) -> str | None:
    text = transcript.replace(" ", "")
    if any(hint in text for hint in _UNKNOWN_HINTS):
        return KidneyStatus.UNKNOWN.value
    if "투석" in text:
        return KidneyStatus.DIALYSIS.value
    if any(hint in text for hint in ("신장병", "신장질환", "콩팥병", "신부전")):
        return KidneyStatus.KIDNEY_DISEASE.value
    if any(hint in text for hint in ("없", "정상", "아니", "괜찮")):
        return KidneyStatus.NONE.value
    return None


def _parse_protein_restriction(transcript: str) -> str | None:
    text = transcript.replace(" ", "")
    if any(hint in text for hint in _UNKNOWN_HINTS):
        return ProteinRestrictionStatus.UNKNOWN.value
    if any(hint in text for hint in ("제한", "줄여", "줄이", "조절")):
        return ProteinRestrictionStatus.RESTRICTED.value
    if any(hint in text for hint in ("없", "정상", "아니", "자유", "괜찮")):
        return ProteinRestrictionStatus.NONE.value
    return None


_PARSERS = {
    VoiceParseField.HEIGHT_CM: _parse_height,
    VoiceParseField.WEIGHT_KG: _parse_weight,
    VoiceParseField.WAIST_CM: _parse_waist,
    VoiceParseField.BIRTH_DATE: _parse_birth_date,
    VoiceParseField.SEX: _parse_sex,
    VoiceParseField.WALKING_PRACTICE: _parse_bool,
    VoiceParseField.STRENGTH_EXERCISE: _parse_bool,
    VoiceParseField.KIDNEY_STATUS: _parse_kidney_status,
    VoiceParseField.PROTEIN_RESTRICTION_STATUS: _parse_protein_restriction,
}


class VoiceParseService:
    """세션/DB에 의존하지 않는 순수 파싱 서비스."""

    @staticmethod
    def parse(field: VoiceParseField, raw_transcript: str) -> VoiceParseResponse:
        value = _PARSERS[field](raw_transcript)
        # 값을 얻으면 항상 사용자에게 되물어 확인(노인 대상, 오인식 방지). 실패면 확인할 게 없다.
        return VoiceParseResponse(field=field, value=value, needs_confirmation=value is not None)

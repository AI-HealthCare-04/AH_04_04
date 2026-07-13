"""OpenAI 파서(OpenAIVoiceParseService)의 응답 정규화(_coerce) 계약 테스트.

실제 OpenAI 호출은 하지 않고 ``_call_openai`` 를 mock 해 모델이 낸 raw value 를 주입한다.
핵심 검증: LLM 이 계약을 벗어난 값(불가능한 날짜, 범위 밖 수치, bool 아닌 예/아니오,
enum 밖 문자열)을 내놓아도 규칙기반 파서와 동일하게 value=None 으로 폼 폴백하는가.
"""
import pytest

from app.dtos.voice_parse import VoiceParseField
from app.services.voice_parse_openai import OpenAIVoiceParseService


async def _parse_with_raw(monkeypatch: pytest.MonkeyPatch, field: VoiceParseField, raw_value: object):
    """모델이 raw_value 를 냈다고 가정하고 parse() 결과를 돌려준다."""

    async def fake_call(field_arg: VoiceParseField, transcript_arg: str) -> object:
        return raw_value

    monkeypatch.setattr(OpenAIVoiceParseService, "_call_openai", fake_call)
    return await OpenAIVoiceParseService.parse(field, "dummy transcript")


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("1958-03-01", "1958-03-01"),  # 정상 날짜
        ("2026-12-31", "2026-12-31"),
        ("1958-13-40", None),  # 불가능한 월/일
        ("1958-02-30", None),  # 2월 30일은 없음
        ("1958/03/01", None),  # 형식 위반(슬래시)
        ("19580301", "1958-03-01"),  # ISO 기본형은 정규화해서 통과
        ("nineteen", None),  # 숫자 아님
        (None, None),  # 모델이 null
        (1958, None),  # 문자열 아님
    ],
)
async def test_birth_date_only_real_calendar_dates(
    monkeypatch: pytest.MonkeyPatch, raw: object, expected: str | None
) -> None:
    result = await _parse_with_raw(monkeypatch, VoiceParseField.BIRTH_DATE, raw)
    assert result.value == expected
    assert result.needs_confirmation is (expected is not None)


@pytest.mark.parametrize(
    "raw,expected",
    [
        (160.0, 160.0),
        ("160", 160.0),  # 문자열 숫자도 허용
        (16, None),  # 상식 범위 밖(오인식)
        (300, None),
        (True, None),  # bool 은 측정치로 취급하지 않음
        (None, None),
    ],
)
async def test_height_range_and_coercion(
    monkeypatch: pytest.MonkeyPatch, raw: object, expected: float | None
) -> None:
    result = await _parse_with_raw(monkeypatch, VoiceParseField.HEIGHT_CM, raw)
    assert result.value == expected


@pytest.mark.parametrize(
    "raw,expected",
    [
        (True, True),
        (False, False),
        ("네", None),  # 문자열은 bool 계약 위반 → None
        (None, None),
    ],
)
async def test_bool_fields_require_real_bool(
    monkeypatch: pytest.MonkeyPatch, raw: object, expected: bool | None
) -> None:
    result = await _parse_with_raw(monkeypatch, VoiceParseField.WALKING_PRACTICE, raw)
    assert result.value == expected


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("male", "male"),
        ("female", "female"),
        ("M", None),  # enum 밖 값
        ("남자", None),  # 서버 enum 은 영문 → 한글은 통과 못 함
    ],
)
async def test_sex_enum_validated(
    monkeypatch: pytest.MonkeyPatch, raw: object, expected: str | None
) -> None:
    result = await _parse_with_raw(monkeypatch, VoiceParseField.SEX, raw)
    assert result.value == expected

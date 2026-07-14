"""OpenAI 파서(OpenAIVoiceParseService)의 응답 정규화(_coerce) 계약 테스트.

실제 OpenAI 호출은 하지 않고 ``_call_openai`` 를 mock 해 모델이 낸 raw value 를 주입한다.
핵심 검증: LLM 이 계약을 벗어난 값(불가능한 날짜, 범위 밖 수치, bool 아닌 예/아니오,
enum 밖 문자열)을 내놓아도 규칙기반 파서와 동일하게 value=None 으로 폼 폴백하는가.
"""
import httpx
import pytest

from app.core import config
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


# ── OpenAI 호출 실패 → 폼 폴백 (라이브 승격 대비: 예외를 500으로 올리지 않는다) ──────────────


class _FakeResponse:
    def __init__(self, payload: object) -> None:
        self._payload = payload

    def raise_for_status(self) -> None:
        pass

    def json(self) -> object:
        return self._payload


def _fake_client_raising(exc: Exception) -> type:
    """post 에서 exc 를 던지는 httpx.AsyncClient 대체."""

    class _Client:
        def __init__(self, *args: object, **kwargs: object) -> None:
            pass

        async def __aenter__(self) -> "_Client":
            return self

        async def __aexit__(self, *args: object) -> bool:
            return False

        async def post(self, *args: object, **kwargs: object) -> _FakeResponse:
            raise exc

    return _Client


def _fake_client_returning(payload: object) -> type:
    """post 에서 payload 를 담은 응답을 주는 httpx.AsyncClient 대체."""

    class _Client:
        def __init__(self, *args: object, **kwargs: object) -> None:
            pass

        async def __aenter__(self) -> "_Client":
            return self

        async def __aexit__(self, *args: object) -> bool:
            return False

        async def post(self, *args: object, **kwargs: object) -> _FakeResponse:
            return _FakeResponse(payload)

    return _Client


async def test_openai_network_error_falls_back_to_none(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config, "OPENAI_API_KEY", "sk-test")  # 키 체크 통과
    monkeypatch.setattr(httpx, "AsyncClient", _fake_client_raising(httpx.ConnectError("boom")))
    result = await OpenAIVoiceParseService.parse(VoiceParseField.HEIGHT_CM, "백육십")
    assert result.value is None
    assert result.needs_confirmation is False


async def test_openai_malformed_response_falls_back_to_none(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config, "OPENAI_API_KEY", "sk-test")
    # choices 키가 없는 비정상 응답 → KeyError → None 폴백
    monkeypatch.setattr(httpx, "AsyncClient", _fake_client_returning({"unexpected": "shape"}))
    result = await OpenAIVoiceParseService.parse(VoiceParseField.WALKING_PRACTICE, "네")
    assert result.value is None
    assert result.needs_confirmation is False

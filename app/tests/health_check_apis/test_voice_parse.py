import pytest
from pydantic import ValidationError

from app.dtos.voice_parse import VoiceParseField, VoiceParseRequest, VoiceParseResponse
from app.services.voice_parse import VoiceParseService


def _parse(field: VoiceParseField, transcript: str) -> VoiceParseResponse:
    return VoiceParseService.parse(field, transcript)


@pytest.mark.parametrize(
    "transcript,expected",
    [
        ("백육십", 160.0),
        ("160", 160.0),
        ("160센티", 160.0),
        ("일미터 육십", 160.0),
        ("1미터60", 160.0),
        ("160센티미터", 160.0),
    ],
)
def test_height_variants(transcript: str, expected: float) -> None:
    result = _parse(VoiceParseField.HEIGHT_CM, transcript)
    assert result.value == expected
    assert result.needs_confirmation is True


@pytest.mark.parametrize(
    "transcript,expected",
    [
        ("오십팔", 58.0),
        ("58키로", 58.0),
        ("58", 58.0),
        ("팔십", 80.0),
    ],
)
def test_weight_variants(transcript: str, expected: float) -> None:
    assert _parse(VoiceParseField.WEIGHT_KG, transcript).value == expected


@pytest.mark.parametrize(
    "transcript,expected",
    [
        ("팔십이", 82.0),
        ("82센티", 82.0),
        ("82", 82.0),
    ],
)
def test_waist_variants(transcript: str, expected: float) -> None:
    assert _parse(VoiceParseField.WAIST_CM, transcript).value == expected


@pytest.mark.parametrize(
    "transcript,expected",
    [
        ("1958년 3월 1일", "1958-03-01"),
        ("오팔년 삼월 일일", "1958-03-01"),
        ("1965년 12월 25일", "1965-12-25"),
    ],
)
def test_birth_date_variants(transcript: str, expected: str) -> None:
    result = _parse(VoiceParseField.BIRTH_DATE, transcript)
    assert result.value == expected
    assert result.needs_confirmation is True


def test_birth_date_rejects_impossible_calendar_date() -> None:
    result = _parse(VoiceParseField.BIRTH_DATE, "1958년 13월 40일")
    assert result.value is None
    assert result.needs_confirmation is False


@pytest.mark.parametrize(
    "transcript,expected",
    [
        ("네", True),
        ("예", True),
        ("해요", True),
        ("아니요", False),
        ("안 해요", False),
        ("없어요", False),
    ],
)
def test_boolean_yes_no(transcript: str, expected: bool) -> None:
    result = _parse(VoiceParseField.WALKING_PRACTICE, transcript)
    assert result.value is expected
    assert result.needs_confirmation is True


@pytest.mark.parametrize("transcript", ["몰라요", "잘 모르겠어요", "글쎄요"])
def test_boolean_unknown_falls_back_to_form(transcript: str) -> None:
    result = _parse(VoiceParseField.STRENGTH_EXERCISE, transcript)
    assert result.value is None
    assert result.needs_confirmation is False


@pytest.mark.parametrize(
    "transcript,expected",
    [
        ("남자", "male"),
        ("여자예요", "female"),
        ("남성", "male"),
    ],
)
def test_sex_variants(transcript: str, expected: str) -> None:
    assert _parse(VoiceParseField.SEX, transcript).value == expected


def test_kidney_and_protein_status() -> None:
    assert _parse(VoiceParseField.KIDNEY_STATUS, "투석 받아요").value == "dialysis"
    assert _parse(VoiceParseField.KIDNEY_STATUS, "없어요").value == "none"
    assert _parse(VoiceParseField.KIDNEY_STATUS, "잘 모르겠어요").value == "unknown"
    assert _parse(VoiceParseField.PROTEIN_RESTRICTION_STATUS, "단백질 제한하고 있어요").value == "restricted"
    assert _parse(VoiceParseField.PROTEIN_RESTRICTION_STATUS, "몰라요").value == "unknown"


def test_out_of_range_measurement_falls_back_to_form() -> None:
    # 오인식으로 상식 범위를 벗어난 값은 확정하지 않고 폼 폴백 신호를 준다.
    result = _parse(VoiceParseField.HEIGHT_CM, "십육")  # 16cm
    assert result.value is None
    assert result.needs_confirmation is False


def test_unparseable_transcript_returns_none_without_error() -> None:
    result = _parse(VoiceParseField.HEIGHT_CM, "어저께 비가 왔어요")
    assert result.value is None
    assert result.needs_confirmation is False


def test_response_preserves_python_types_in_json() -> None:
    height = _parse(VoiceParseField.HEIGHT_CM, "백육십").model_dump(mode="json")
    walking = _parse(VoiceParseField.WALKING_PRACTICE, "네").model_dump(mode="json")

    assert height == {"field": "height_cm", "value": 160.0, "needs_confirmation": True}
    assert walking["value"] is True  # bool이 float(1.0)로 뭉개지지 않아야 한다


def test_request_rejects_unknown_field() -> None:
    with pytest.raises(ValidationError):
        VoiceParseRequest.model_validate({"field": "blood_type", "raw_transcript": "에이형"})


def test_request_rejects_blank_transcript() -> None:
    with pytest.raises(ValidationError):
        VoiceParseRequest.model_validate({"field": "height_cm", "raw_transcript": "   "})

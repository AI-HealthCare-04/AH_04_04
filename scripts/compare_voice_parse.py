"""규칙기반 파서 vs OpenAI 파서 비교 하네스.

멘토링 질문("OpenAI API 로 STT 파싱이 구동될지")을 숫자로 확인하기 위한 실험 스크립트다.
팀이 정리한 필드별 검증 케이스를 두 파서에 똑같이 넣고, 정답 대비 정오답과 정확도(%)를 출력한다.

실행:
    # 레포 루트(.env 에 OPENAI_API_KEY 설정)에서
    uv run --no-sync python -m scripts.compare_voice_parse

    # 모델 바꿔 실행
    OPENAI_MODEL=gpt-4o uv run --no-sync python -m scripts.compare_voice_parse

OPENAI_API_KEY 가 없으면 규칙기반 결과만 출력한다(비용/네트워크 없이 표는 확인 가능).
"""
import asyncio

from app.core import config
from app.dtos.voice_parse import VoiceParseField
from app.services.voice_parse import VoiceParseService
from app.services.voice_parse_openai import OpenAIVoiceParseService

# (필드, 음성 원문, 기대값) — 팀 확정 검증 케이스.
CASES: list[tuple[VoiceParseField, str, object]] = [
    (VoiceParseField.HEIGHT_CM, "백육십", 160.0),
    (VoiceParseField.HEIGHT_CM, "160", 160.0),
    (VoiceParseField.HEIGHT_CM, "160센티", 160.0),
    (VoiceParseField.HEIGHT_CM, "일미터 육십", 160.0),
    (VoiceParseField.WEIGHT_KG, "오십팔", 58.0),
    (VoiceParseField.WEIGHT_KG, "58키로", 58.0),
    (VoiceParseField.WAIST_CM, "팔십이", 82.0),
    (VoiceParseField.WAIST_CM, "82센티", 82.0),
    (VoiceParseField.BIRTH_DATE, "1958년 3월 1일", "1958-03-01"),
    (VoiceParseField.BIRTH_DATE, "오팔년 삼월 일일", "1958-03-01"),
    (VoiceParseField.WALKING_PRACTICE, "네", True),
    (VoiceParseField.WALKING_PRACTICE, "아니요", False),
    (VoiceParseField.WALKING_PRACTICE, "몰라요", None),
    (VoiceParseField.WALKING_PRACTICE, "잘 모르겠어요", None),
]


def _fmt(value: object) -> str:
    return "null" if value is None else repr(value)


def _mark(actual: object, expected: object) -> str:
    return "O" if actual == expected else "X"


async def main() -> None:
    use_openai = bool(config.OPENAI_API_KEY)
    if not use_openai:
        print("⚠️  OPENAI_API_KEY 가 없어 규칙기반만 출력합니다. .env 에 키를 넣으면 OpenAI 비교가 활성화됩니다.\n")
    else:
        print(f"OpenAI 모델: {config.OPENAI_MODEL}\n")

    header = f"{'field':<26} {'원문':<16} {'기대값':<14} {'규칙기반':<14} {'':<2} {'OpenAI':<14} {'':<2}"
    print(header)
    print("-" * len(header))

    rule_ok = openai_ok = openai_total = 0
    for field, transcript, expected in CASES:
        rule_value = VoiceParseService.parse(field, transcript).value
        rule_hit = _mark(rule_value, expected)
        rule_ok += rule_hit == "O"

        if use_openai:
            try:
                openai_value: object = (await OpenAIVoiceParseService.parse(field, transcript)).value
            except Exception as exc:  # noqa: BLE001 - 실험 스크립트는 실패도 표에 보여준다
                openai_value = f"<error: {exc}>"
            openai_hit = _mark(openai_value, expected)
            openai_ok += openai_hit == "O"
            openai_total += 1
            openai_cell = f"{_fmt(openai_value):<14} {openai_hit:<2}"
        else:
            openai_cell = f"{'-':<14} {'-':<2}"

        print(
            f"{field.value:<26} {transcript:<16} {_fmt(expected):<14} "
            f"{_fmt(rule_value):<14} {rule_hit:<2} {openai_cell}"
        )

    total = len(CASES)
    print("-" * len(header))
    print(f"규칙기반 정확도: {rule_ok}/{total} = {rule_ok / total * 100:.1f}%")
    if use_openai and openai_total:
        print(f"OpenAI  정확도: {openai_ok}/{openai_total} = {openai_ok / openai_total * 100:.1f}%")


if __name__ == "__main__":
    asyncio.run(main())

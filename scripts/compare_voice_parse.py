"""규칙기반 파서 vs OpenAI 파서 비교 하네스.

멘토링 질문("OpenAI API 로 STT 파싱이 구동될지")을 숫자로 확인하기 위한 실험 스크립트다.
두 개의 코퍼스로 나눠 비교한다.
  1) 정형 발화(clean)  — 팀 확정 검증 케이스(짧고 제약된 발화).
  2) 자연 발화(natural) — 리뷰(#49) 반영: 군더더기·일상형·부정/모호·단위누락 등
     실제 노인 발화에 가까운 케이스. 규칙기반이 어디서 무너지고 OpenAI 가 그걸
     메우는지(또는 못 메우는지)를 본다.

※ 이 비교는 STT '인식률'이 아니라, **같은 transcript 를 두 파서 중 누가 더 정확히
  field/value 로 파싱하는가**를 본다. natural 코퍼스의 기대값은 라벨링 판단이 들어가므로
  실기기 SpeechRecognizer 실 transcript 로 교체·보강하는 것이 이상적이다.

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

Case = tuple[VoiceParseField, str, object]

# 1) 정형 발화 — 팀 확정 검증 케이스.
CLEAN_CASES: list[Case] = [
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

# 2) 자연 발화 — 리뷰(#49, Earthworm-jk) 반영. 기대값은 잠정 라벨(실 transcript 로 교체 권장).
NATURAL_CASES: list[Case] = [
    # 군더더기가 섞인 자연발화 (숫자 주변에 조사·부사)
    (VoiceParseField.HEIGHT_CM, "제 키는 한 백육십 정도 되는 것 같아요", 160.0),
    (VoiceParseField.WEIGHT_KG, "몸무게는 한 오십팔 킬로쯤 나가요", 58.0),
    (VoiceParseField.WAIST_CM, "허리둘레는 뭐 팔십이 정도 될걸요", 82.0),
    # 일상형 응답 (예/아니오를 문장으로)
    (VoiceParseField.WALKING_PRACTICE, "요즘은 동네를 가끔 한 바퀴씩 걸어요", True),
    (VoiceParseField.STRENGTH_EXERCISE, "근력운동 그런 건 따로 안 해요", False),
    # 부정·모호 표현
    (VoiceParseField.KIDNEY_STATUS, "투석은 안 받아요", "none"),
    (VoiceParseField.PROTEIN_RESTRICTION_STATUS, "단백질 제한 그런 건 잘 모르겠어요", "unknown"),
    (VoiceParseField.BIRTH_DATE, "생일이 정확히는 잘 기억이 안 나요", None),
    # 띄어쓰기·단위 누락 / 자연스러운 숫자 표현
    (VoiceParseField.HEIGHT_CM, "한 백 육십쯤이요", 160.0),
    (VoiceParseField.BIRTH_DATE, "천구백오십팔년 삼월 일일이요", "1958-03-01"),
]

CORPORA: list[tuple[str, list[Case]]] = [
    ("정형 발화(clean)", CLEAN_CASES),
    ("자연 발화(natural)", NATURAL_CASES),
]


def _fmt(value: object) -> str:
    return "null" if value is None else repr(value)


def _mark(actual: object, expected: object) -> str:
    return "O" if actual == expected else "X"


async def _run_corpus(name: str, cases: list[Case], use_openai: bool) -> tuple[int, int, int]:
    """한 코퍼스를 두 파서로 돌려 표를 출력하고 (규칙기반 정답수, OpenAI 정답수, 케이스수)를 반환."""
    header = f"{'field':<26} {'원문':<34} {'기대값':<14} {'규칙기반':<14} {'':<2} {'OpenAI':<14} {'':<2}"
    print(f"\n### {name}  (n={len(cases)})")
    print(header)
    print("-" * len(header))

    rule_ok = openai_ok = 0
    for field, transcript, expected in cases:
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
            openai_cell = f"{_fmt(openai_value):<14} {openai_hit:<2}"
        else:
            openai_cell = f"{'-':<14} {'-':<2}"

        print(
            f"{field.value:<26} {transcript:<34} {_fmt(expected):<14} "
            f"{_fmt(rule_value):<14} {rule_hit:<2} {openai_cell}"
        )

    total = len(cases)
    print("-" * len(header))
    print(f"규칙기반: {rule_ok}/{total} = {rule_ok / total * 100:.1f}%", end="")
    if use_openai:
        print(f"   |   OpenAI: {openai_ok}/{total} = {openai_ok / total * 100:.1f}%")
    else:
        print()
    return rule_ok, openai_ok, total


async def main() -> None:
    use_openai = bool(config.OPENAI_API_KEY)
    if not use_openai:
        print("⚠️  OPENAI_API_KEY 가 없어 규칙기반만 출력합니다. .env 에 키를 넣으면 OpenAI 비교가 활성화됩니다.")
    else:
        print(f"OpenAI 모델: {config.OPENAI_MODEL}")

    rule_total = openai_total = case_total = 0
    for name, cases in CORPORA:
        rule_ok, openai_ok, total = await _run_corpus(name, cases, use_openai)
        rule_total += rule_ok
        openai_total += openai_ok
        case_total += total

    print("\n=== 종합 ===")
    print(f"규칙기반: {rule_total}/{case_total} = {rule_total / case_total * 100:.1f}%", end="")
    if use_openai:
        print(f"   |   OpenAI: {openai_total}/{case_total} = {openai_total / case_total * 100:.1f}%")
    else:
        print()


if __name__ == "__main__":
    asyncio.run(main())

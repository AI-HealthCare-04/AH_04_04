from app.core.utils.nickname import _ADJECTIVES, _NOUNS, generate_nickname


def test_nickname_is_adjective_space_noun_from_word_banks() -> None:
    adjective, _, noun = generate_nickname().partition(" ")
    assert adjective in _ADJECTIVES
    assert noun in _NOUNS
    assert noun != ""  # 공백으로 정확히 두 토큰이어야 한다


def test_nickname_stays_within_column_limit() -> None:
    # nickname 컬럼/DTO 제한은 50자. 어떤 조합도 그 안에 들어와야 한다.
    longest = max(len(a) for a in _ADJECTIVES) + 1 + max(len(n) for n in _NOUNS)
    assert longest <= 50


def test_word_banks_have_no_duplicates() -> None:
    # 중복 단어는 확률만 왜곡하고 조합 수를 줄인다(회귀 방지).
    assert len(_ADJECTIVES) == len(set(_ADJECTIVES))
    assert len(_NOUNS) == len(set(_NOUNS))


def test_generates_varied_nicknames() -> None:
    # 순수 랜덤이라 이론상 같은 값만 나올 수도 있으나, 조합 수가 커서 사실상 다양해야 한다.
    results = {generate_nickname() for _ in range(50)}
    assert len(results) > 1

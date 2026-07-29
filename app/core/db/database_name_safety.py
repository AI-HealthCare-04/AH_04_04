import re

_EPHEMERAL_DATABASE_NAME_PATTERN = re.compile(r"^(?:test|qa)_[A-Za-z0-9_]+$", re.IGNORECASE)


def validate_ephemeral_database_name(name: str, *, application_database: str | None = None) -> str:
    """삭제 가능한 테스트·QA 전용 MySQL 데이터베이스 이름인지 검증한다."""
    if not _EPHEMERAL_DATABASE_NAME_PATTERN.fullmatch(name):
        raise ValueError(
            "임시 DB 이름은 'test_' 또는 'qa_'로 시작하고 영문자·숫자·밑줄만 포함해야 합니다."
        )
    if len(name) > 64:
        raise ValueError("MySQL DB 이름은 64자를 초과할 수 없습니다.")
    if application_database and name.casefold() == application_database.casefold():
        raise ValueError("임시 DB 이름은 애플리케이션 DB_NAME과 같을 수 없습니다.")
    return name

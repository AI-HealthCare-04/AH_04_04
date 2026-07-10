# =====================================================================================
# build_database_url() — DB 접속 URL 조립 단위 테스트. DB 불필요.
# 비밀번호에 URL 특수문자(@ : / # ! 등)가 있어도 URL이 깨지지 않아야 한다.
#   (구버그: f-string 수동 조립이 escape를 못 해, 비번의 @가 host 구분자로 먹혀 연결 URL이 깨짐.)
# =====================================================================================
import pytest
from sqlalchemy import URL

from app.core import config
from app.core.db.session import build_database_url


def test_build_database_url_preserves_special_char_credentials(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(config, "DB_USER", "root")
    monkeypatch.setattr(config, "DB_PASSWORD", "Pa@ss!:w/d#x")
    monkeypatch.setattr(config, "DB_HOST", "db.internal")
    monkeypatch.setattr(config, "DB_PORT", 3306)
    monkeypatch.setattr(config, "DB_NAME", "ai_health")

    url = build_database_url()

    assert isinstance(url, URL)
    # 자격증명은 구조화 필드에 원문 그대로 보존된다.
    assert url.username == "root"
    assert url.password == "Pa@ss!:w/d#x"
    # 비밀번호의 @ 때문에 host가 어긋나지 않는다(구버그는 '!@db.internal'로 깨졌음).
    assert url.host == "db.internal"
    assert url.port == 3306
    assert url.database == "ai_health"
    assert url.drivername == "mysql+asyncmy"
    assert url.query == {"charset": "utf8mb4"}

    # 렌더링 시 특수문자는 percent-escape되어 드라이버가 파싱할 수 있다.
    rendered = url.render_as_string(hide_password=False)
    assert "Pa@ss" not in rendered  # raw @가 그대로 남으면 안 된다
    assert "%40" in rendered  # @ -> %40

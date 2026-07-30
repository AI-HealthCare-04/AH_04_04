# =====================================================================================
# scripts/publish_terms.py — 약관 md → HTML 변환 검증(#244, 지영 리뷰 #268 블로커).
#
# 핵심 관심사 둘:
#   1) 표·중첩 목록·인용 등 실제 약관 문서가 쓰는 문법이 구조 있는 HTML 로 변환되는가.
#   2) [스냅샷 성격] 레포의 실제 4개 문서를 전부 변환했을 때 원시 Markdown 기호(|, **, `,
#      줄머리 -, >)가 본문에 남지 않는가 — 문서에 새 문법이 추가되면 이 검사가 먼저 잡는다.
# =====================================================================================
import re

import pytest

from scripts.publish_terms import TERMS_DIR, md_to_html


def test_table_becomes_thead_tbody_without_raw_pipes() -> None:
    """표는 thead/tbody 로 변환되고 파이프 원문이 남지 않는다."""
    md = (
        "| 구분 | 항목 | 수집 방법 |\n"
        "|---|---|---|\n"
        "| 프로필 | 생년월일, 성별 | 이용자 입력 |\n"
        "| 활동 기록 | 걸음수 | 서비스 이용 |\n"
    )
    out = md_to_html(md, "표 테스트")

    assert "<table>" in out and "</table>" in out
    assert "<thead><tr><th>구분</th><th>항목</th><th>수집 방법</th></tr></thead>" in out
    assert "<td>프로필</td><td>생년월일, 성별</td><td>이용자 입력</td>" in out
    assert "|" not in out, "표 변환 후 파이프 원문이 남으면 안 된다"
    assert "---" not in out, "헤더 구분 줄이 본문으로 새면 안 된다"


def test_indented_sub_items_nest_inside_same_ol() -> None:
    """들여쓴 하위 항목은 같은 <ol> 안의 중첩 목록이 된다(번호 리셋 방지)."""
    # privacy-1.0.md §3 실제 패턴: 번호 목록 안에 "   - ※ …" 하위 항목.
    md = "1. 첫째\n2. 둘째\n   - ※ 하위 비고\n3. 셋째\n"
    out = md_to_html(md, "중첩 목록")

    flat = out.replace("\n", "")
    assert out.count("<ol>") == 1, "하위 항목 때문에 <ol> 이 끊기면 번호가 1부터 다시 시작한다"
    assert '<li value="2">둘째<ul><li>※ 하위 비고</li></ul></li>' in flat, (
        "하위 항목은 직전 <li> 안의 중첩 목록이어야 한다"
    )
    assert '<li value="3">셋째' in flat, "md 원문 번호가 value 로 보존돼야 한다"
    assert "<p>- " not in out and "<li>- " not in out, "하위 항목이 문단으로 새어 '- ' 원문이 노출되면 안 된다"


def test_bare_quote_line_stays_inside_blockquote() -> None:
    """빈 인용 줄('>')은 인용 블록 안의 문단 구분으로 처리된다."""
    # privacy-1.0.md 머리말 실제 패턴: "> ⚠️ …" / ">" / "> 시행일: …"
    md = "> 첫 문단\n>\n> 둘째 문단\n"
    out = md_to_html(md, "인용")

    assert out.count("<blockquote>") == 1
    assert "<p>첫 문단</p>" in out and "<p>둘째 문단</p>" in out
    assert "<p>&gt;</p>" not in out and "<p>></p>" not in out, "빈 인용 줄 '>' 이 본문으로 새면 안 된다"


def test_inline_code_becomes_code_tag() -> None:
    """인라인 코드는 <code> 로 변환된다."""
    out = md_to_html("환경변수(`TERMS_PRIVACY_URL`)로 주입합니다.", "코드")
    assert "<code>TERMS_PRIVACY_URL</code>" in out
    assert "`" not in out


@pytest.mark.parametrize("src", sorted(TERMS_DIR.glob("*-*.md")), ids=lambda p: p.name)
def test_real_terms_docs_leave_no_raw_markdown(src) -> None:
    """실제 약관 4개 문서 변환 결과에 원시 Markdown 기호가 남지 않는다."""
    out = md_to_html(src.read_text(encoding="utf-8"), src.stem)

    # <style> 은 검사 대상이 아니다 — 본문(<body>)만 본다.
    body = out.split("<body>", 1)[1]

    assert "|" not in body, f"{src.name}: 표가 원문 그대로 새었다"
    assert "**" not in body, f"{src.name}: 굵게 원문이 남았다"
    assert "`" not in body, f"{src.name}: 인라인 코드 원문이 남았다"
    for leaked in ("<p># ", "<p>## ", "<p>- ", "<p>&gt;", "<p>></p>"):
        assert leaked not in body, f"{src.name}: '{leaked}' — 블록 문법이 문단으로 새었다"
    # 번호 목록 원문("1. …")이 문단으로 새지 않았는지.
    assert not re.search(r"<p>\d+\.\s", body), f"{src.name}: 번호 목록이 문단으로 새었다"


def test_all_four_terms_docs_exist() -> None:
    """게시 대상 4개 문서가 모두 존재한다(terms_catalog 과의 어긋남 방지)."""
    # 게시 대상 문서 집합이 terms_catalog 과 어긋나게 줄면 조용히 빠진 채 배포되므로 여기서 고정한다.
    names = {p.name for p in TERMS_DIR.glob("*-*.md")}
    assert names == {"service-1.0.md", "privacy-1.0.md", "sensitive-health-1.0.md", "marketing-1.0.md"}

# =====================================================================================
# 약관 md → 정적 HTML 변환기 (#244 §2 약관 URL 원천화).
#
# 원천은 레포 docs/terms/*.md 하나다. 약관을 고치면:
#   1) docs/terms/<종류>-<버전>.md 수정 (버전이 오르면 파일명·terms_catalog.py 도 갱신)
#   2) uv run --no-sync python -m scripts.publish_terms   → build/terms/*.html 생성
#   3) scp 로 서버 /opt/ah0404/media/terms 에 업로드 (docs/terms/README.md 반영 절차 참고)
#
# 외부 의존성 없이 표준 라이브러리만 쓴다. 지원 문법은 약관 문서에 실제로 쓰이는 것 전부:
#   제목(h1/h2) · 인용(>) · 목록(번호/불릿, 한 단계 중첩) · 표(| … |) · 구분선(---) · **굵게** · `코드` · 문단.
#   이 밖의 문법을 문서에 새로 쓰면 tests/test_publish_terms.py 의 "원시 기호 잔류" 검사가 잡는다.
# 시니어 사용자 기준 큰 글자·넉넉한 행간.
# =====================================================================================
from __future__ import annotations

import html
import re
from pathlib import Path

TERMS_DIR = Path(__file__).resolve().parent.parent / "docs" / "terms"
OUT_DIR = Path(__file__).resolve().parent.parent / "build" / "terms"

_STYLE = """
  body { max-width: 720px; margin: 0 auto; padding: 24px 20px 48px;
         font-family: system-ui, -apple-system, "Apple SD Gothic Neo", "Malgun Gothic", sans-serif;
         font-size: 18px; line-height: 1.7; color: #1c1c1e; background: #fff; }
  h1 { font-size: 26px; line-height: 1.4; }
  h2 { font-size: 21px; margin-top: 1.6em; }
  blockquote { margin: 1em 0; padding: 12px 16px; background: #fff8e1;
               border-left: 4px solid #f0b429; border-radius: 4px; }
  li { margin: 0.3em 0; }
  table { border-collapse: collapse; width: 100%; margin: 1em 0; font-size: 16px; }
  th, td { border: 1px solid #d5d5da; padding: 8px 10px; text-align: left; vertical-align: top; }
  th { background: #f4f4f6; }
  code { background: #f2f2f4; border-radius: 4px; padding: 1px 5px; font-size: 0.95em; }
  hr { border: 0; border-top: 1px solid #d5d5da; margin: 1.6em 0; }
  @media (prefers-color-scheme: dark) {
    body { color: #ececec; background: #111; }
    blockquote { background: #2a2410; }
    th, td { border-color: #3c3c41; }
    th { background: #222226; }
    code { background: #26262b; }
    hr { border-top-color: #3c3c41; }
  }
"""

_OL_ITEM = re.compile(r"^(\s*)(\d+)\.\s+(.*)$")
_UL_ITEM = re.compile(r"^(\s*)-\s+(.*)$")


def _inline(text: str) -> str:
    """이스케이프 후 **굵게** 와 `코드` 를 처리한다(약관 문서에 쓰이는 인라인 문법 전부)."""
    out = html.escape(text, quote=False)
    out = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", out)
    return re.sub(r"`([^`]+)`", r"<code>\1</code>", out)


def _cells(line: str) -> list[str]:
    """표 한 줄을 셀 리스트로 나눈다. `| a | b |` → ["a", "b"] (양끝 파이프 제거 후 분리)."""
    return [c.strip() for c in line.strip().strip("|").split("|")]


def _is_separator_row(line: str) -> bool:
    """`|---|---|` 형태의 헤더 구분 줄인지 판별한다."""
    return all(re.fullmatch(r":?-{3,}:?", c) for c in _cells(line))


def _render_table(lines: list[str], i: int, body: list[str]) -> int:
    """i 부터 이어지는 `|` 줄들을 <table> 로 변환한다(지영 리뷰 #268 블로커 — 표가 원문 그대로 새던 문제).
    둘째 줄이 구분 줄이면 첫 줄을 <th> 헤더로 승격한다. 다음 파싱 위치를 반환."""
    rows: list[str] = []
    while i < len(lines) and lines[i].lstrip().startswith("|"):
        rows.append(lines[i])
        i += 1
    data = rows
    body.append("<table>")
    if len(rows) >= 2 and _is_separator_row(rows[1]):
        head = "".join(f"<th>{_inline(c)}</th>" for c in _cells(rows[0]))
        body.append(f"<thead><tr>{head}</tr></thead>")
        data = rows[2:]
    body.append("<tbody>")
    for row in data:
        body.append("<tr>" + "".join(f"<td>{_inline(c)}</td>" for c in _cells(row)) + "</tr>")
    body.append("</tbody>")
    body.append("</table>")
    return i


def _parse_list_item(line: str) -> tuple[int, str, str, str | None] | None:
    """목록 줄이면 (들여쓰기 폭, 종류 ol|ul, 내용, ol 원문 번호)를 반환한다."""
    m = _OL_ITEM.match(line)
    if m:
        return len(m.group(1)), "ol", m.group(3), m.group(2)
    m = _UL_ITEM.match(line)
    if m:
        return len(m.group(1)), "ul", m.group(2), None
    return None


def _render_list(lines: list[str], i: int, body: list[str]) -> int:
    """i 부터 이어지는 목록 줄들을 하나의 <ol>/<ul> 로 변환한다. 다음 파싱 위치를 반환.

    - 들여쓴 항목은 직전 항목 **안의** 중첩 목록으로 붙인다 — 종전에는 문단으로 새어
      `- ※ …` 원문이 그대로 노출되고, 열려 있던 <ol> 이 끊겨 번호가 1부터 다시 시작했다.
    - <li value=N> 으로 md 원문 번호를 보존한다(빈 줄 등으로 목록이 나뉘어도 번호 유지).
    """
    first = _parse_list_item(lines[i])
    assert first is not None  # 호출부가 목록 줄에서만 부른다
    top_indent, top_kind = first[0], first[1]
    body.append(f"<{top_kind}>")
    li_open = False  # 중첩 목록을 안에 넣기 위해 마지막 <li> 를 열어 둔다
    nested: str | None = None  # 열려 있는 중첩 목록 종류
    while i < len(lines):
        item = _parse_list_item(lines[i]) if lines[i].strip() else None
        if item is None:
            break
        indent, kind, content, number = item
        value = f' value="{number}"' if kind == "ol" and number else ""
        if indent > top_indent and li_open:
            if nested is None:
                nested = kind
                body.append(f"<{nested}>")
            body.append(f"<li{value}>{_inline(content)}</li>")
        else:
            if nested is not None:
                body.append(f"</{nested}>")
                nested = None
            if li_open:
                body.append("</li>")
            body.append(f"<li{value}>{_inline(content)}")
            li_open = True
        i += 1
    if nested is not None:
        body.append(f"</{nested}>")
    if li_open:
        body.append("</li>")
    body.append(f"</{top_kind}>")
    return i


def _render_single_line(line: str) -> str | None:
    """한 줄로 끝나는 블록(제목·구분선)이면 그 HTML 을, 아니면 None 을 반환한다."""
    if line.startswith("# "):
        return f"<h1>{_inline(line[2:])}</h1>"
    if line.startswith("## "):
        return f"<h2>{_inline(line[3:])}</h2>"
    if re.fullmatch(r"-{3,}", line.strip()):
        # 구분선(#268 리뷰 재검토): sensitive-health·marketing 문서의 `---` 가 <p>---</p> 로 새던 문제.
        return "<hr>"
    return None


def md_to_html(md: str, title: str) -> str:
    lines = [raw.rstrip() for raw in md.splitlines()]
    body: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        single = _render_single_line(line)
        if not line.strip():
            i += 1
        elif single is not None:
            body.append(single)
            i += 1
        elif line.startswith(">"):
            # 인용 블록. 빈 인용 줄(">")은 문단 구분 — 종전에는 p 로 새어 ">" 원문이 노출됐다.
            body.append("<blockquote>")
            while i < len(lines) and lines[i].startswith(">"):
                text = lines[i][1:].lstrip()
                if text:
                    body.append(f"<p>{_inline(text)}</p>")
                i += 1
            body.append("</blockquote>")
        elif line.lstrip().startswith("|"):
            i = _render_table(lines, i, body)
        elif _parse_list_item(line) is not None:
            i = _render_list(lines, i, body)
        else:
            body.append(f"<p>{_inline(line)}</p>")
            i += 1

    joined = "\n".join(body)
    return (
        "<!doctype html>\n"
        '<html lang="ko">\n<head>\n<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
        f"<title>{html.escape(title)}</title>\n<style>{_STYLE}</style>\n</head>\n<body>\n"
        f"{joined}\n</body>\n</html>\n"
    )


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    sources = sorted(TERMS_DIR.glob("*-*.md"))  # README.md 는 게시 대상이 아니다
    if not sources:
        raise SystemExit(f"약관 md 가 없습니다: {TERMS_DIR}")
    for src in sources:
        md = src.read_text(encoding="utf-8")
        first = next((line for line in md.splitlines() if line.startswith("# ")), "# 약관")
        out = OUT_DIR / f"{src.stem}.html"
        out.write_text(md_to_html(md, first[2:].strip()), encoding="utf-8")
        print(f"생성: {out.relative_to(OUT_DIR.parent.parent)}")
    print(
        "\n업로드(예):\n"
        "  ssh -i ~/.ssh/AH_04_04.pem ubuntu@<EC2_IP> 'sudo mkdir -p /opt/ah0404/media/terms && sudo chown ubuntu /opt/ah0404/media/terms'\n"
        "  scp -i ~/.ssh/AH_04_04.pem build/terms/*.html ubuntu@<EC2_IP>:/opt/ah0404/media/terms/"
    )


if __name__ == "__main__":
    main()

# =====================================================================================
# 약관 md → 정적 HTML 변환기 (#244 §2 약관 URL 원천화).
#
# 원천은 레포 docs/terms/*.md 하나다. 약관을 고치면:
#   1) docs/terms/<종류>-<버전>.md 수정 (버전이 오르면 파일명·terms_catalog.py 도 갱신)
#   2) uv run --no-sync python -m scripts.publish_terms   → build/terms/*.html 생성
#   3) scp 로 서버 /opt/ah0404/media/terms 에 업로드 (docs/terms/README.md 반영 절차 참고)
#
# 외부 의존성 없이 표준 라이브러리만 쓴다(부분 마크다운: 제목/목록/굵게/인용/문단 —
# 약관 문서에 실제로 쓰이는 문법만 지원한다). 시니어 사용자 기준 큰 글자·넉넉한 행간.
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
  @media (prefers-color-scheme: dark) {
    body { color: #ececec; background: #111; }
    blockquote { background: #2a2410; }
  }
"""


def _inline(text: str) -> str:
    """이스케이프 후 **굵게** 만 처리한다(약관 문서에 쓰이는 유일한 인라인 문법)."""
    out = html.escape(text, quote=False)
    return re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", out)


def _classify(line: str) -> tuple[str, str]:
    """줄을 (블록 종류, 내용)으로 분류한다. 종류: h1 | h2 | blockquote | ol | ul | p"""
    if line.startswith("# "):
        return "h1", line[2:]
    if line.startswith("## "):
        return "h2", line[3:]
    if line.startswith("> "):
        return "blockquote", line[2:]
    if re.match(r"^\d+\.\s", line):
        return "ol", re.sub(r"^\d+\.\s", "", line)
    if line.startswith("- "):
        return "ul", line[2:]
    return "p", line


# 여러 줄이 하나의 컨테이너로 묶이는 블록과 (여는 태그, 줄 태그) 매핑.
_CONTAINERS = {"ol": ("<ol>", "li"), "ul": ("<ul>", "li"), "blockquote": ("<blockquote>", "p")}
_SINGLES = {"h1": "h1", "h2": "h2", "p": "p"}


def md_to_html(md: str, title: str) -> str:
    body: list[str] = []
    mode: str | None = None  # 열려 있는 컨테이너 블록

    def close() -> None:
        nonlocal mode
        if mode is not None:
            body.append(f"</{mode}>")
        mode = None

    for raw in md.splitlines():
        line = raw.rstrip()
        if not line.strip():
            close()
            continue
        kind, content = _classify(line)
        if kind in _SINGLES:
            close()
            tag = _SINGLES[kind]
            body.append(f"<{tag}>{_inline(content)}</{tag}>")
            continue
        open_tag, line_tag = _CONTAINERS[kind]
        if mode != kind:
            close()
            body.append(open_tag)
            mode = kind
        body.append(f"<{line_tag}>{_inline(content)}</{line_tag}>")
    close()

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

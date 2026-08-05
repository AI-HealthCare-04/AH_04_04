package com.aihealthcare.ah0404.ui.text

/**
 * 폭 0 의 WORD JOINER(U+2060) — "여기서 줄을 끊지 마라"는 표시만 하고 화면에는 보이지 않는다.
 */
private const val WORD_JOINER = '⁠'

private fun Char.isHangulSyllable() = this in '가'..'힣'

/**
 * 한글이 **어절 단위로** 접히게 한다 — 음절 사이에 [WORD_JOINER] 를 끼워 어절 안에 끊길 자리를 없앤다.
 * 남는 끊길 자리는 띄어쓰기뿐이라 `몸 상/태와` 대신 `몸 / 상태와` 로 접힌다.
 *
 * 한글은 규칙상 음절 사이 아무 데서나 끊을 수 있어, 글꼴을 키우거나 폭이 좁아지면 단어가 반으로
 * 갈린다. `LineBreak` 프리셋으로는 해결되지 않는다(Paragraph 는 Simple 과 결과가 같고, Heading 의
 * WordBreak.Phrase 는 공백을 쓰지 않는 언어 기준이라 한국어에 일관되게 걸리지 않는다 — 실기기 확인).
 *
 * ⚠️ **화면에 그리는 순간에만 쓴다.** 결과 문자열을 상수·strings.xml·서버 요청에 저장하면
 *   보이지 않는 문자 때문에 문자열 비교(테스트 `assertEquals`, `onNodeWithText`)와 소스 검색이 어긋난다.
 *
 * 띄어쓰기가 없는 문자열은 그대로 돌려준다: 어절이 하나뿐이라 얻을 게 없고, 오히려 줄보다 긴 단어는
 * 끊겨야 화면 밖으로 넘치지 않는다.
 */
fun keepKoreanWords(text: String): String {
    if (!text.contains(' ')) return text
    return buildString(text.length) {
        text.forEachIndexed { i, c ->
            append(c)
            val next = text.getOrNull(i + 1) ?: return@forEachIndexed
            if (c.isHangulSyllable() && next.isHangulSyllable()) append(WORD_JOINER)
        }
    }
}

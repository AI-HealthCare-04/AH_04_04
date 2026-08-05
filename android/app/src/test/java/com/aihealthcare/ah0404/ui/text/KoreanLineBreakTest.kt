package com.aihealthcare.ah0404.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class KoreanLineBreakTest {

    @Test
    fun `어절 안에만 WORD JOINER 를 넣고 띄어쓰기는 끊길 자리로 남긴다`() {
        // '몸' 은 한 음절이라 안에 넣을 자리가 없고, '상태와' 는 음절 사이 두 곳에 들어간다.
        assertEquals("몸 상⁠태⁠와", keepKoreanWords("몸 상태와"))
    }

    @Test
    fun `띄어쓰기가 없으면 원본을 그대로 돌려준다`() {
        // 어절이 하나뿐이라 얻을 게 없다. 줄보다 길면 끊겨야 화면 밖으로 안 넘친다.
        val single = "비슷해요"
        assertSame(single, keepKoreanWords(single))
    }

    @Test
    fun `한글이 아닌 문자 사이에는 넣지 않는다`() {
        // 숫자·기호·영문 경계는 건드리지 않는다 — 점수 표기('76점')나 단위가 섞인 문구를 위해서다.
        assertEquals("주 1일 하⁠면", keepKoreanWords("주 1일 하면"))
        // 'AI' 와 '검' 사이(영문-한글)에는 안 들어가고, 한글끼리인 '검사'·'결과' 안에만 들어간다.
        assertEquals("AI 검⁠사 결⁠과", keepKoreanWords("AI 검사 결과"))
    }

    @Test
    fun `길이만 늘 뿐 보이는 글자는 그대로다`() {
        // 넣은 문자를 도로 빼면 원본과 같아야 한다 — 화면 문구가 바뀌지 않았다는 뜻.
        val original = "이 결과가 평소 느끼는 몸 상태와 비슷한가요?"
        assertEquals(original, keepKoreanWords(original).replace("⁠", ""))
    }
}

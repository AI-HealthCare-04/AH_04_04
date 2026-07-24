package com.aihealthcare.ah0404.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingAssessmentValidationTest {

    @Test
    fun `공백과 잘못된 값은 검사 완료 입력으로 인정하지 않는다`() {
        assertNull(parseChairStandSeconds(""))
        assertNull(parseChairStandSeconds("   "))
        assertNull(parseChairStandSeconds("abc"))
        assertNull(parseChairStandSeconds("0"))
        assertNull(parseChairStandSeconds("-1.5"))
        assertNull(parseChairStandSeconds("NaN"))
        assertNull(parseChairStandSeconds("Infinity"))
    }

    @Test
    fun `유한한 양수는 공백을 제거해 초 단위 값으로 변환한다`() {
        assertEquals(12.5, parseChairStandSeconds("12.5")!!, 0.0)
        assertEquals(9.25, parseChairStandSeconds(" 9.25 ")!!, 0.0)
    }
}

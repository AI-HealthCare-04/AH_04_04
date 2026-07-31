package com.aihealthcare.ah0404.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingBackNavigationTest {
    @Test
    fun onboarding_steps_have_expected_previous_destination() {
        assertEquals(OnbStep.WELCOME, previousOnboardingStep(OnbStep.TERMS))
        assertEquals(OnbStep.TERMS, previousOnboardingStep(OnbStep.PROFILE))
        assertEquals(OnbStep.PROFILE, previousOnboardingStep(OnbStep.ASSESSMENT))
    }

    @Test
    fun first_step_does_not_navigate_back() {
        // 결과화면(RESULT) 제거(#299) 후엔 WELCOME 만 이전 단계가 없다. 완주는 step 이 아니라 finished 로 알린다.
        assertNull(previousOnboardingStep(OnbStep.WELCOME))
    }
}

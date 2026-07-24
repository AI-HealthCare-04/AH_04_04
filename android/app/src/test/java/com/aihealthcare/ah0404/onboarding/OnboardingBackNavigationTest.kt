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
    fun first_and_completed_steps_do_not_navigate_back() {
        assertNull(previousOnboardingStep(OnbStep.WELCOME))
        assertNull(previousOnboardingStep(OnbStep.RESULT))
    }
}

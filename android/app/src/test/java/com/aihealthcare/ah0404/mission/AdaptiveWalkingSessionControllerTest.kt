package com.aihealthcare.ah0404.mission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AdaptiveWalkingSessionController.shouldUseStepCounter 순수 규칙 검증.
 * 만보기(TYPE_STEP_COUNTER)는 **하드웨어 지원 && ACTIVITY_RECOGNITION 허용** 둘 다일 때만 쓰고,
 * 하나라도 빠지면 가속도계로 폴백한다(측정이 죽지 않게).
 */
class AdaptiveWalkingSessionControllerTest {

    @Test
    fun `하드웨어 지원 + 권한 허용이면 만보기를 쓴다`() {
        assertTrue(
            AdaptiveWalkingSessionController.shouldUseStepCounter(
                stepCounterSupported = true,
                activityRecognitionGranted = true,
            ),
        )
    }

    @Test
    fun `권한이 거부되면 만보기를 쓰지 않는다(가속도계 폴백)`() {
        assertFalse(
            AdaptiveWalkingSessionController.shouldUseStepCounter(
                stepCounterSupported = true,
                activityRecognitionGranted = false,
            ),
        )
    }

    @Test
    fun `만보기 하드웨어가 없으면 쓰지 않는다(가속도계 폴백)`() {
        assertFalse(
            AdaptiveWalkingSessionController.shouldUseStepCounter(
                stepCounterSupported = false,
                activityRecognitionGranted = true,
            ),
        )
    }

    @Test
    fun `둘 다 없으면 당연히 쓰지 않는다`() {
        assertFalse(
            AdaptiveWalkingSessionController.shouldUseStepCounter(
                stepCounterSupported = false,
                activityRecognitionGranted = false,
            ),
        )
    }
}

package com.aihealthcare.ah0404.mission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [#199 데모 스톱갭] 화면 유지(FLAG_KEEP_SCREEN_ON) phase 경계 검증(리뷰 #206).
 *
 * 측정 중(MEASURING)에만 화면을 유지하고, READY/DONE 에서는 무기한 켜 두지 않는다.
 * 순수 함수(keepScreenOnForPhase)라 화면/기기 없이 경계를 단위 검증한다.
 */
class KeepScreenOnPolicyTest {

    @Test
    fun `측정 중(MEASURING)에는 화면을 유지한다`() {
        assertTrue(keepScreenOnForPhase(WalkingSessionViewModel.Phase.MEASURING))
    }

    @Test
    fun `준비(READY) 단계에서는 화면을 유지하지 않는다`() {
        assertFalse(keepScreenOnForPhase(WalkingSessionViewModel.Phase.READY))
    }

    @Test
    fun `종료(DONE) 단계에서는 화면을 유지하지 않는다`() {
        assertFalse(keepScreenOnForPhase(WalkingSessionViewModel.Phase.DONE))
    }

    @Test
    fun `MEASURING 외 모든 단계는 화면 유지 대상이 아니다`() {
        // 향후 Phase 가 늘어나도 '측정 중에만 유지' 계약이 유지되는지 회귀 방어.
        WalkingSessionViewModel.Phase.values().forEach { phase ->
            val expected = phase == WalkingSessionViewModel.Phase.MEASURING
            assertTrue(
                "phase=$phase 의 화면 유지 판정이 계약과 다름",
                keepScreenOnForPhase(phase) == expected,
            )
        }
    }
}

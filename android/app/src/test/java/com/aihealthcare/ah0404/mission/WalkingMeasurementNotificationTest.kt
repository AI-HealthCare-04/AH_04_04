package com.aihealthcare.ah0404.mission

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [#199 증분 A] 걷기 측정 FGS 지속 알림 본문 텍스트 고정(순수 함수 검증).
 * 서비스/알림 프레임워크 없이 표시 문구만 회귀 방어한다.
 */
class WalkingMeasurementNotificationTest {

    @Test
    fun `걸음 수를 본문에 표시한다`() {
        assertEquals("걷기 측정 중 · 0보", walkingMeasurementNotificationText(0))
        assertEquals("걷기 측정 중 · 1보", walkingMeasurementNotificationText(1))
        assertEquals("걷기 측정 중 · 128보", walkingMeasurementNotificationText(128))
    }

    @Test
    fun `공급자 없으면 현재 걸음 수는 0`() {
        WalkingMeasurement.stepsProvider = null
        assertEquals(0, WalkingMeasurement.currentSteps())
    }

    @Test
    fun `공급자를 설정하면 그 값을 읽는다`() {
        WalkingMeasurement.stepsProvider = { 42 }
        assertEquals(42, WalkingMeasurement.currentSteps())
        WalkingMeasurement.stepsProvider = null // 테스트 간 전역 상태 정리
    }
}

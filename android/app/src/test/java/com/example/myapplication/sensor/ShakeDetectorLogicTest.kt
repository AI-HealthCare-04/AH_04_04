package com.example.myapplication.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShakeDetectorLogicTest {

    private lateinit var logic: ShakeDetectorLogic

    @Before
    fun setUp() {
        logic = ShakeDetectorLogic()
    }

    // ── 기본 감지 ────────────────────────────────

    @Test
    fun `정지 상태(크기 약 9_8) 에서는 카운트 안 됨`() {
        // 중력만 있는 상태 (0, 0, 9.8) → 크기 ≈ 9.8, 임계값 미만
        val result = logic.processSample(0f, 0f, 9.8f, 1000L)
        assertFalse(result)
        assertEquals(0, logic.count)
    }

    @Test
    fun `임계값 초과하는 강한 흔들기는 감지`() {
        // 크기 20 m/s² → 임계값(13) 초과
        val result = logic.processSample(0f, 0f, 20f, 1000L)
        assertTrue(result)
        assertEquals(1, logic.count)
    }

    @Test
    fun `임계값 경계값 미만에서는 미감지`() {
        // 크기 = SHAKE_THRESHOLD - 0.1f 로 임계값보다 약간 아래
        val belowThreshold = ShakeDetectorLogic.SHAKE_THRESHOLD - 0.1f
        val result = logic.processSample(belowThreshold, 0f, 0f, 1000L)
        assertFalse(result)
        assertEquals(0, logic.count)
    }

    // ── 최소 간격 제어 ────────────────────────────

    @Test
    fun `최소 간격 안에 온 두 번째 흔들기는 카운트 안 됨`() {
        logic.processSample(0f, 0f, 20f, 1000L)
        // 200ms 후 → MIN_SHAKE_INTERVAL_MS(500) 미만
        val result = logic.processSample(0f, 0f, 20f, 1200L)
        assertFalse(result)
        assertEquals(1, logic.count)
    }

    @Test
    fun `최소 간격 이후 두 번째 흔들기는 카운트됨`() {
        logic.processSample(0f, 0f, 20f, 1000L)
        // 600ms 후 → MIN_SHAKE_INTERVAL_MS(500) 초과
        val result = logic.processSample(0f, 0f, 20f, 1600L)
        assertTrue(result)
        assertEquals(2, logic.count)
    }

    @Test
    fun `연속 세 번 흔들기 누적 카운트`() {
        logic.processSample(0f, 0f, 20f, 0L)
        logic.processSample(0f, 0f, 20f, 600L)
        logic.processSample(0f, 0f, 20f, 1200L)
        assertEquals(3, logic.count)
    }

    // ── 리셋 ─────────────────────────────────────

    @Test
    fun `reset 후 count 0으로 초기화`() {
        logic.processSample(0f, 0f, 20f, 1000L)
        logic.reset()
        assertEquals(0, logic.count)
    }

    @Test
    fun `reset 후 직후 흔들기도 감지됨 (타이머 초기화 확인)`() {
        logic.processSample(0f, 0f, 20f, 1000L)
        logic.reset()
        // reset으로 lastShakeTime = 0 이므로 100ms 후에도 감지돼야 함
        val result = logic.processSample(0f, 0f, 20f, 1100L)
        assertTrue(result)
        assertEquals(1, logic.count)
    }

    // ── 다축 흔들기 ─────────────────────────────

    @Test
    fun `X Y Z 복합 가속도로 크기 계산 후 감지`() {
        // (10, 10, 10) → 크기 ≈ 17.3 m/s² > 13 임계값
        val result = logic.processSample(10f, 10f, 10f, 1000L)
        assertTrue(result)
    }
}

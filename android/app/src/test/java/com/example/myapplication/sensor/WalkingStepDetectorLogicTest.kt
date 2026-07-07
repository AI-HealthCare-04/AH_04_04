package com.example.myapplication.sensor

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WalkingStepDetectorLogicTest {

    private lateinit var logic: WalkingStepDetectorLogic

    @Before
    fun setUp() {
        logic = WalkingStepDetectorLogic()
    }

    // 한 걸음 = 큰 가속도(피크) 샘플 + 작은 가속도(골) 샘플.
    // 저주파 필터를 확실히 넘고 내려오도록 18 / 2 를 사용.
    // 피크 시각은 high 샘플의 시각 = peakTimeMs.
    private fun oneStep(peakTimeMs: Long) {
        logic.processSample(0f, 0f, 18f, peakTimeMs)        // 상승 → 피크
        logic.processSample(0f, 0f, 2f, peakTimeMs + 120L)  // 하강 → 임계값 아래로 복귀
    }

    /** 규칙적인 간격(600ms ≈ 100보/분)으로 여러 걸음 시뮬레이션 */
    private fun walkSteps(count: Int, startMs: Long = 0L, intervalMs: Long = 600L) {
        for (i in 0 until count) {
            oneStep(startMs + i * intervalMs)
        }
    }

    // ── 일회성 동작은 카운트 안 됨 (오탐 방지 핵심) ──────────

    @Test
    fun `피크 1회(일회성 동작)는 카운트 안 됨`() {
        oneStep(0L)
        assertEquals(0, logic.count)
        assertEquals(WalkingStepDetectorLogic.State.IDLE, logic.state)
    }

    @Test
    fun `피크 2회는 아직 보행 판정 전이라 카운트 안 됨`() {
        walkSteps(2)
        assertEquals(0, logic.count)
        assertEquals(WalkingStepDetectorLogic.State.IDLE, logic.state)
    }

    // ── 연속 보행은 카운트됨 ──────────────────────────────

    @Test
    fun `연속 3걸음이면 보행 진입하며 웜업분 소급 카운트`() {
        walkSteps(3)
        assertEquals(3, logic.count)
        assertEquals(WalkingStepDetectorLogic.State.WALKING, logic.state)
    }

    @Test
    fun `연속 10걸음이면 정확히 10 카운트`() {
        walkSteps(10)
        assertEquals(10, logic.count)
    }

    // ── 중복 진동 제거 ───────────────────────────────────

    @Test
    fun `최소 간격 안의 중복 피크는 무시`() {
        // 정상 걸음 사이에 100ms(MIN_PEAK_INTERVAL_MS 미만) 간격의 가짜 피크를 끼워넣음
        logic.processSample(0f, 0f, 18f, 0L)   // 피크 1
        logic.processSample(0f, 0f, 2f, 50L)   // 골
        logic.processSample(0f, 0f, 18f, 100L) // 100ms 뒤 중복 피크 → 무시돼야 함
        assertEquals(1, logic.consecutivePeaks)
    }

    // ── 불규칙(간격이 너무 김)하면 보행 아님 ─────────────

    @Test
    fun `간격이 최대 허용을 넘으면 규칙 카운트가 리셋되어 보행 안 됨`() {
        oneStep(0L)
        oneStep(3000L) // 3초 간격: MAX_PEAK_INTERVAL_MS(2000) 및 TIMEOUT(2500) 초과
        oneStep(6000L)
        assertEquals(0, logic.count)
        assertEquals(WalkingStepDetectorLogic.State.IDLE, logic.state)
    }

    // ── 정지 후 IDLE 복귀 ───────────────────────────────

    @Test
    fun `보행 중 오래 멈추면 IDLE로 복귀`() {
        walkSteps(3) // WALKING 진입, count 3
        assertEquals(WalkingStepDetectorLogic.State.WALKING, logic.state)

        // 마지막 피크(t=1200) 이후 timeout 초과된 시점에 조용한 샘플 주입
        logic.processSample(0f, 0f, 2f, 1200L + 3000L)
        assertEquals(WalkingStepDetectorLogic.State.IDLE, logic.state)
    }

    @Test
    fun `IDLE 복귀 후 한 걸음은 재웜업 필요해서 카운트 안 늘어남`() {
        walkSteps(3)                 // count 3, WALKING
        oneStep(1200L + 3000L)       // timeout 후 첫 피크 → IDLE에서 다시 시작
        assertEquals(3, logic.count) // 아직 3걸음 더 못 채웠으므로 그대로
    }

    // ── 리셋 ─────────────────────────────────────────────

    @Test
    fun `reset 후 모든 상태 초기화`() {
        walkSteps(5)
        logic.reset()
        assertEquals(0, logic.count)
        assertEquals(0, logic.consecutivePeaks)
        assertEquals(WalkingStepDetectorLogic.State.IDLE, logic.state)
    }

    @Test
    fun `reset 후 다시 3걸음 걸으면 정상 카운트`() {
        walkSteps(5)
        logic.reset()
        walkSteps(3, startMs = 10000L)
        assertEquals(3, logic.count)
    }
}

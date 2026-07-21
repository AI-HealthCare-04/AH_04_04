package com.aihealthcare.ah0404.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A-4a(#89) 정확도 회귀 테스트.
 *
 * 현장 실측(정인님 2차 측정 가이드)에서 눈으로 확인할 시나리오를 **결정론적 합성 신호**로 고정한다.
 * 기존 WalkingStepDetectorLogicTest 는 진입 기준을 3으로 낮춰 '게이팅 로직'만 봤다면, 여기서는
 * **배포 가설값(게이트 10 / 최소간격 250 / 최대 2000)** 그대로 두고 정확도 목표를 검증한다.
 *
 *  1) 이중봉우리(더블범프): 한 걸음이 두 번 잡히는 보행 — 최소간격을 올리면 병합돼 실제 걸음수에 수렴 (1차 버그 20보→34)
 *  2) 느린 독립보행: 미탐(FN) 없이 전량 집계
 *  3) 앉았다 일어나기 / 짧은 폰 흔들기: 게이트 미달로 오탐(FP) 0
 *  4) 최소/최대 간격 경계값
 *
 * ⚠️ 임계값은 아직 '가설값'이다. 이 테스트는 "현재 로직이 이렇게 동작한다"를 못 박는 회귀선이며,
 *    실측으로 DEFAULT_* 가 확정/변경되면 여기 기대값도 함께 갱신한다. (근거: docs/sensor_walking_gate_a4a.md)
 */
class WalkingStepDetectorAccuracyTest {

    private lateinit var logic: WalkingStepDetectorLogic

    @Before
    fun setUp() {
        logic = WalkingStepDetectorLogic() // 기본(가설)값 그대로 사용
    }

    /** 피크 1개 = 임계 위 샘플 + 골(임계 아래 복귀) 샘플. 시각은 피크 기준. */
    private fun peak(atMs: Long, valleyAfterMs: Long = 60L) {
        logic.processSample(0f, 0f, 18f, atMs)
        logic.processSample(0f, 0f, 2f, atMs + valleyAfterMs)
    }

    private fun peaksAt(times: List<Long>) = times.forEach { peak(it) }

    /** 한 걸음이 '두 번' 잡히는 보행: 진짜 걸음 피크 + bumpGap 뒤 두 번째 봉우리. */
    private fun doubleBumpStride(startMs: Long, bumpGapMs: Long) {
        peak(startMs)
        peak(startMs + bumpGapMs)
    }

    // ── 1. 이중봉우리(더블범프) — #89 핵심 버그 ─────────────────────────

    @Test
    fun `이중봉우리 보행은 최소간격 250에서 2배로 과다카운트된다(1차 버그 재현)`() {
        logic.minPeakIntervalMs = 250L
        // 진짜 20보. 각 걸음마다 250ms 뒤 두 번째 봉우리, 걸음 간격 600ms(≈100보/분).
        repeat(20) { i -> doubleBumpStride(i * 600L, bumpGapMs = 250L) }
        assertEquals(40, logic.count) // 20보인데 40 — 실측 "20보→34"의 원인(이중 카운트)
    }

    @Test
    fun `이중봉우리 보행도 최소간격을 400으로 올리면 실제 걸음수(20)에 수렴한다`() {
        logic.minPeakIntervalMs = 400L
        repeat(20) { i -> doubleBumpStride(i * 600L, bumpGapMs = 250L) }
        assertEquals(20, logic.count) // 두 번째 봉우리(250ms)가 병합돼 과다카운트 해소
    }

    // ── 2. 느린 독립보행 — 미탐(FN) 방지 ────────────────────────────

    @Test
    fun `느린 독립보행 20보는 미탐 없이 전량 집계된다`() {
        // 1100ms 간격(≈55보/분) — 최대간격 2000·타임아웃 2500 안이라 리듬 유지.
        repeat(20) { i -> peak(i * 1100L) }
        assertEquals(20, logic.count)
        assertEquals(WalkingStepDetectorLogic.State.WALKING, logic.state)
        assertTrue("느린 독립보행(셔플 제외)은 합격선 이상 집계돼야 함", logic.count >= 17)
    }

    // ── 3. 오탐(FP) — 게이트가 비보행을 걸러낸다 ─────────────────────

    @Test
    fun `앉았다 일어나기 수준의 불규칙 5피크는 게이트10에서 0카운트`() {
        // 일어서기 5회 ≈ 큰 피크 5개. 연속 10걸음 게이트에 못 미쳐 보행으로 확정되지 않는다.
        peaksAt(listOf(0L, 700L, 1400L, 2100L, 2800L))
        assertEquals(0, logic.count)
        assertEquals(WalkingStepDetectorLogic.State.IDLE, logic.state)
    }

    @Test
    fun `짧은 폰 흔들기(100ms 빠른 진동)는 최소간격에 걸려 0카운트`() {
        // 100ms 간격 빠른 진동 8회 → 대부분 최소간격(250ms) 미만이라 버려지고 게이트 미달.
        repeat(8) { i -> peak(i * 100L, valleyAfterMs = 40L) }
        assertEquals(0, logic.count)
        assertEquals(WalkingStepDetectorLogic.State.IDLE, logic.state)
    }

    // ── 4. 경계값 ──────────────────────────────────────────────────

    @Test
    fun `간격이 최소간격과 정확히 같으면(250) 중복으로 버리지 않는다`() {
        logic.peaksToStartWalking = 2
        peak(0L)
        peak(250L) // 정확히 250ms → 조건이 'interval < 250'이라 유효 피크로 인정
        assertEquals(2, logic.count)
    }

    @Test
    fun `간격이 최대허용과 정확히 같으면(2000) 규칙이 유지된다`() {
        logic.peaksToStartWalking = 2
        peak(0L)
        peak(2000L) // 정확히 2000ms → 'interval <= 2000'이라 리듬 유지(리셋 아님)
        assertEquals(2, logic.count)
        assertEquals(WalkingStepDetectorLogic.State.WALKING, logic.state)
    }
}

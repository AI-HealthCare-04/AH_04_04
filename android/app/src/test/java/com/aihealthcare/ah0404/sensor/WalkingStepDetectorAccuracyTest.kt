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
 * **실측 확정값(게이트 10 / 최소간격 350 / 최대 1000)** 그대로 두고 정확도 목표를 검증한다.
 *   (max는 2차 재측정으로 900→1000 상향: 대상 하한 60보/분=1000ms 포함, 앉기 1200ms는 계속 차단. 리뷰 #121)
 *
 *  1) 이중봉우리(더블범프): 한 걸음이 두 번 잡히는 보행 — 최소간격을 올리면 병합돼 실제 걸음수에 수렴 (1차 버그 20보→34)
 *  2) 초저속/느린(범위 밖): 최대간격(1000ms) 밖 리듬이라 걷기로 인정 안 됨(과다 방지 우선)
 *  3) 정지 상태 앉았다 일어나기 / 짧은 폰 흔들기: 오탐(FP) 0 — 대역 밖 느린 리듬은 max 축소로 차단
 *  4) 최소/최대 간격 경계값(350 / 1000)
 *  5) ⚠️ 알려진 한계: '보행 직후 곧바로 앉기'(≈882ms=68보/분)는 정상 대역 안이라 간격으로 분리 불가 →
 *     과다카운트가 남는다. v1 미보장을 회귀 테스트로 '명시적으로' 고정한다(개선되면 이 테스트를 갱신).
 *
 * 근거·결정: docs/sensor_walking_gate_a4a.md (#89, 3차 실측 2026-07-22, §5-5 알려진 한계).
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

    // ── 2. 초저속/느린(범위 밖) — 정상 대역만 인정 ──────────────────

    @Test
    fun `느린 보행(1100ms 간격)은 최대간격 1000 밖이라 걷기로 인정하지 않는다`() {
        // 정상 대역(하한 60보/분=1000ms)만 걷기로 본다. 1100ms(≈55보/분)는 최대간격(1000) 초과 →
        // 매 걸음 리듬이 끊겨 게이트에 도달하지 못한다. 초저속을 못 세는 대신 앉기 오탐을 막는 의도적 트레이드오프(#89).
        repeat(20) { i -> peak(i * 1100L) }
        assertEquals(0, logic.count)
        assertEquals(WalkingStepDetectorLogic.State.IDLE, logic.state)
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
    fun `정지 상태 앉았다 일어나기의 느린 리듬(1200ms)은 최대간격 1000 밖이라 많이 반복해도 0카운트`() {
        // 실측(#89): 정지 상태 앉기 5회가 걸음 13·10으로 오탐됐음(느린 리듬 ≈47~52보/분, 간격 ≈1200ms).
        // max를 1000으로 좁혀, 피크가 12번 나도 매번 리듬이 끊겨 게이트에 도달하지 못한다 → FP 차단(1200 > 1000).
        repeat(12) { i -> peak(i * 1200L) }
        assertEquals(0, logic.count)
        assertEquals(WalkingStepDetectorLogic.State.IDLE, logic.state)
    }

    // ── 5. 알려진 한계(v1 미보장) — 명시적 고정 ──────────────────────

    @Test
    fun `알려진 한계 - 보행 직후 곧바로 앉기는 대역 안(882ms) 리듬이라 간격으로 분리되지 않아 과다카운트가 남는다`() {
        // 3차 실측(#89, 2026-07-22 SM-F766N): 정상 20보 → 곧바로 앉기 5회 = 28.
        // 앉기 피크 간격 ≈882ms(68보/분)는 정상 보행 대역(500~1000ms) 한가운데 → interval <= max(1000) 통과.
        // 간격이라는 단일 신호로는 정상 보행과 분리 불가 → v1 미보장(docs §5-5).
        // 이 테스트는 '앉기 FP가 해소됐다'는 잘못된 주장을 막고, 현재 동작을 정직하게 고정한다.
        repeat(20) { i -> peak(i * 600L) }          // 정상 보행 20보(≈100보/분, 대역 안)
        val afterWalk = logic.count
        assertEquals(20, afterWalk)                  // 보행 구간은 정확
        repeat(5) { i -> peak(12_000L + i * 882L) } // 곧바로 앉기 5회(≈68보/분, 여전히 대역 안)
        // 앉기 피크가 대역 안이라 WALKING이 유지되며 계속 카운트된다 → 과다.
        assertTrue(
            "보행 직후 앉기는 현재 분리 불가(알려진 한계)여서 과다카운트가 남아야 하는데 count=${logic.count} " +
                "(개선됐다면 §5-5·문서·이 테스트를 함께 갱신)",
            logic.count > afterWalk,
        )
    }

    @Test
    fun `짧은 폰 흔들기(100ms 빠른 진동)는 최소간격에 걸려 0카운트`() {
        // 100ms 간격 빠른 진동 8회 → 대부분 최소간격(350ms) 미만이라 버려지고 게이트 미달.
        repeat(8) { i -> peak(i * 100L, valleyAfterMs = 40L) }
        assertEquals(0, logic.count)
        assertEquals(WalkingStepDetectorLogic.State.IDLE, logic.state)
    }

    // ── 4. 경계값 ──────────────────────────────────────────────────

    @Test
    fun `간격이 최소간격과 정확히 같으면(350) 중복으로 버리지 않는다`() {
        logic.peaksToStartWalking = 2
        peak(0L)
        peak(350L) // 정확히 350ms → 조건이 'interval < 350'이라 유효 피크로 인정
        assertEquals(2, logic.count)
    }

    @Test
    fun `간격이 최대허용과 정확히 같으면(1000) 규칙이 유지된다`() {
        logic.peaksToStartWalking = 2
        peak(0L)
        peak(1000L) // 정확히 1000ms → 'interval <= 1000'이라 리듬 유지(리셋 아님)
        assertEquals(2, logic.count)
        assertEquals(WalkingStepDetectorLogic.State.WALKING, logic.state)
    }

    @Test
    fun `간격이 최대허용을 넘으면(1001) 리듬이 끊겨 게이트에 도달하지 않는다`() {
        logic.peaksToStartWalking = 2
        peak(0L)
        peak(1001L) // 1001ms → 'interval <= 1000'을 벗어나 연속 카운트 리셋 → 진입 실패
        assertEquals(0, logic.count)
        assertEquals(WalkingStepDetectorLogic.State.IDLE, logic.state)
    }
}

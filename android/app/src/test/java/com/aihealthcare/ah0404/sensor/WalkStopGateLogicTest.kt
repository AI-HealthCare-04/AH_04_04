package com.aihealthcare.ah0404.sensor

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * [#131] WalkStopGateLogic — '보행 직후 앉기' 소급차감 게이트 순수 로직 검증.
 * 오프라인 하네스 analyze_waveform.py 의 `_retro_selftest` 와 동일한 3시나리오를 합성 신호로 재현한다.
 *
 * 합성 규칙(50Hz):
 *  - 보행 = x 축 진동(진폭 A) → 윈도우 평균 제거 후 동적RMS ≈ A/√2. A=4 → RMS≈2.83(≥기준 1.0).
 *  - 정지 = x=0 → 동적RMS≈0 (기준의 STOP_RATIO=0.4 배 미만으로 붕괴).
 *  - z=9.8 은 상수라 윈도우 평균 제거 시 잔차 0(중력 성분 무시됨).
 */
class WalkStopGateLogicTest {

    private val dtMs = 20L        // 50Hz
    private val freqHz = 1.8      // 보행 케이던스 유사(RMS 크기와는 무관)

    /** [fromMs, toMs) 구간을 진폭 amp 의 x 진동으로 게이트에 흘린다. */
    private fun feed(gate: WalkStopGateLogic, fromMs: Long, toMs: Long, amp: Double) {
        var t = fromMs
        while (t < toMs) {
            val x = (amp * sin(2 * PI * freqHz * (t / 1000.0))).toFloat()
            gate.processSample(x, 0f, 9.8f, t)
            t += dtMs
        }
    }

    @Test
    fun `연속 보행은 아무 걸음도 취소하지 않는다`() {
        val gate = WalkStopGateLogic()
        // 0~16s 계속 보행(붕괴 없음). 걸음을 촘촘히 등록.
        var t = 0L
        while (t < 16_000L) {
            val x = (4.0 * sin(2 * PI * freqHz * (t / 1000.0))).toFloat()
            gate.processSample(x, 0f, 9.8f, t)
            if (t % 560L < dtMs) gate.onStepCounted(t) // ≈107보/분 페이스
            t += dtMs
        }
        assertEquals(0, gate.canceledSteps)
    }

    @Test
    fun `보행 직후 앉기의 정지 선언 창 걸음을 취소한다`() {
        val gate = WalkStopGateLogic()
        feed(gate, 0L, 12_000L, amp = 4.0)      // 0~12s 정상 보행 → e_ref 확정
        feed(gate, 12_000L, 13_500L, amp = 0.0) // 붕괴 시작(선언 전)

        // 붕괴 구간에서 오탐으로 세어진 걸음들(정지 선언 창 (12s, 15s] 안). 선언 전에 버퍼에 등록돼야 취소된다.
        gate.onStepCounted(12_200L)
        gate.onStepCounted(12_800L)
        gate.onStepCounted(13_400L)

        // 확인창(2s)을 채워 정지 선언 트리거(온셋≈13s → T≈15s, 창 (12s,15s]).
        feed(gate, 13_500L, 16_000L, amp = 0.0)

        assertEquals(3, gate.canceledSteps)
    }

    @Test
    fun `애초에 보행이 아니면(저에너지) 정지 선언하지 않는다`() {
        val gate = WalkStopGateLogic()
        feed(gate, 0L, 12_000L, amp = 0.3)      // 기준 RMS < WALK_REF_MIN_DYN → '보행 아님'
        feed(gate, 12_000L, 16_600L, amp = 0.0) // 이후 붕괴여도 정지 전환 없음
        gate.onStepCounted(12_500L)
        gate.onStepCounted(13_000L)
        assertEquals(0, gate.canceledSteps)
    }

    @Test
    fun `reset 은 취소 카운트를 0으로 되돌린다`() {
        val gate = WalkStopGateLogic()
        feed(gate, 0L, 12_000L, amp = 4.0)
        feed(gate, 12_000L, 16_600L, amp = 0.0)
        gate.onStepCounted(12_500L)
        gate.reset()
        assertEquals(0, gate.canceledSteps)
    }
}

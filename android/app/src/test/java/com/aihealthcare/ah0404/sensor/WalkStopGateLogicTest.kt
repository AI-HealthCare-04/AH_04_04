package com.aihealthcare.ah0404.sensor

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * [#131] WalkStopGateLogic — '보행 직후 앉기' 소급차감 게이트 순수 로직 검증.
 * 오프라인 하네스 analyze_waveform.py 의 소급차감 시나리오 + 리뷰 #215 보강(앵커/flush/오취소/재무장)을
 * 합성 신호로 재현한다.
 *
 * 합성 규칙(50Hz):
 *  - 보행 = x 축 진동(진폭 A) → 윈도우 평균 제거 후 동적RMS ≈ A/√2. A=4 → RMS≈2.83(≥기준 1.0).
 *  - 정지 = x=0 → 동적RMS≈0 (기준의 STOP_RATIO=0.4 배 미만으로 붕괴).
 *  - z=9.8 은 상수라 윈도우 평균 제거 시 잔차 0(중력 성분 무시됨).
 *  - 실제 앱 순서와 동일하게, 걸음 표본이면 onStepCounted() 를 processSample() **앞**에 호출한다.
 */
class WalkStopGateLogicTest {

    private val dtMs = 20L        // 50Hz
    private val freqHz = 1.8      // 보행 케이던스 유사(RMS 크기와는 무관)

    /**
     * [fromMs, toMs) 구간을 진폭 amp 의 x 진동으로 흘린다. stepPeriodMs>0 이면 그 주기로 걸음을 등록한다.
     * amp=0·stepPeriodMs=0 이면 '정지(걸음 없음)' 구간.
     */
    private fun feed(
        gate: WalkStopGateLogic,
        fromMs: Long,
        toMs: Long,
        amp: Double,
        stepPeriodMs: Long = 0L,
    ) {
        var t = fromMs
        while (t < toMs) {
            if (stepPeriodMs > 0 && (t - fromMs) % stepPeriodMs < dtMs) gate.onStepCounted(t)
            val x = (amp * sin(2 * PI * freqHz * (t / 1000.0))).toFloat()
            gate.processSample(x, 0f, 9.8f, t)
            t += dtMs
        }
    }

    @Test
    fun `연속 보행은 아무 걸음도 취소하지 않는다`() {
        val gate = WalkStopGateLogic()
        feed(gate, 0L, 16_000L, amp = 4.0, stepPeriodMs = 560L)
        assertEquals(0, gate.canceledSteps)
    }

    @Test
    fun `보행 직후 앉기의 정지 선언 창 걸음을 취소한다`() {
        val gate = WalkStopGateLogic()
        feed(gate, 0L, 11_000L, amp = 4.0, stepPeriodMs = 560L) // 0~11s 정상 보행 → e_ref 확정
        // 붕괴 구간에서 오탐으로 세어진 걸음들(정지 선언 창 (11s, 14s] 안).
        gate.onStepCounted(11_200L)
        gate.onStepCounted(11_800L)
        gate.onStepCounted(12_400L)
        feed(gate, 11_000L, 16_000L, amp = 0.0) // 붕괴 지속 → 온셋≈12s, 선언 T≈14s
        assertEquals(3, gate.canceledSteps)
    }

    @Test
    fun `애초에 보행이 아니면(저에너지) 정지 선언하지 않는다`() {
        val gate = WalkStopGateLogic()
        feed(gate, 0L, 12_000L, amp = 0.3, stepPeriodMs = 560L) // 기준 RMS < 임계 → '보행 아님'
        gate.onStepCounted(12_500L)
        feed(gate, 12_000L, 16_000L, amp = 0.0)
        assertEquals(0, gate.canceledSteps)
    }

    @Test
    fun `초기 정지 후 늦게 걷기 시작해도 기준을 잡아 앉기를 취소한다`() {
        // 리뷰 #215: e_ref 앵커가 세션 시작이 아니라 '첫 걸음'이어야 함(초기 8s 정지 시 영구 비활성 방지).
        val gate = WalkStopGateLogic()
        feed(gate, 0L, 8_000L, amp = 0.0)                        // 초기 8s 정지(걸음 없음)
        feed(gate, 8_000L, 19_000L, amp = 4.0, stepPeriodMs = 560L) // 8s 후 보행 시작 → 여기 앵커
        gate.onStepCounted(19_200L)
        gate.onStepCounted(19_800L)
        gate.onStepCounted(20_400L)
        feed(gate, 19_000L, 24_000L, amp = 0.0)                  // 붕괴 → 선언 → 취소
        assertEquals(3, gate.canceledSteps)
    }

    @Test
    fun `flushOnStop 은 확인창 전 종료(앉으며 종료)에도 소급차감을 적용한다`() {
        // 리뷰 #215 결정: 앉은 직후 ~3초 내 종료 시나리오. 온셋이 최소 지속 조건을 넘으면 즉시 차감.
        val gate = WalkStopGateLogic()
        feed(gate, 0L, 10_000L, amp = 4.0, stepPeriodMs = 560L)
        gate.onStepCounted(10_200L)
        gate.onStepCounted(10_800L)
        gate.onStepCounted(11_400L)
        feed(gate, 10_000L, 12_800L, amp = 0.0) // 온셋≈11s, 확인창(2s) 못 채움(자동 선언 전)
        val flushed = gate.flushOnStop()         // 온셋 지속 ≥1s → 즉시 차감
        assertEquals(3, flushed)
        assertEquals(3, gate.canceledSteps)
    }

    @Test
    fun `확인창을 못 채운 짧은 붕괴 후 회복하면 오취소하지 않는다`() {
        val gate = WalkStopGateLogic()
        feed(gate, 0L, 10_000L, amp = 4.0, stepPeriodMs = 560L)
        gate.onStepCounted(10_200L)
        feed(gate, 10_000L, 11_000L, amp = 0.0)                    // ~1s 짧은 붕괴(확인창 미달)
        feed(gate, 11_000L, 16_000L, amp = 4.0, stepPeriodMs = 560L) // 보행 회복 → 온셋 취소
        assertEquals(0, gate.canceledSteps)
    }

    @Test
    fun `정지 선언 후 보행이 회복되면 재무장하여 두 번째 앉기도 취소한다`() {
        val gate = WalkStopGateLogic()
        feed(gate, 0L, 10_000L, amp = 4.0, stepPeriodMs = 560L)
        gate.onStepCounted(10_200L)
        feed(gate, 10_000L, 13_000L, amp = 0.0)                    // 1차 앉기 → 선언·취소
        feed(gate, 13_000L, 20_000L, amp = 4.0, stepPeriodMs = 560L) // 재보행 → 재무장
        gate.onStepCounted(20_200L)
        feed(gate, 20_000L, 23_000L, amp = 0.0)                    // 2차 앉기 → 선언·취소
        assertEquals(2, gate.canceledSteps)
    }

    @Test
    fun `reset 은 취소 카운트를 0으로 되돌린다`() {
        val gate = WalkStopGateLogic()
        feed(gate, 0L, 11_000L, amp = 4.0, stepPeriodMs = 560L)
        gate.onStepCounted(11_200L)
        feed(gate, 11_000L, 16_000L, amp = 0.0)
        gate.reset()
        assertEquals(0, gate.canceledSteps)
    }
}

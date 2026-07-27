package com.aihealthcare.ah0404.sensor

import kotlin.math.sqrt

/**
 * #131 소급차감(retroactive subtraction) 게이트 — '보행 직후 곧바로 앉기' 과다카운트 억제 (순수 로직).
 *
 * ## 왜 필요한가
 * [WalkingStepDetectorLogic] 은 피크 **간격**만으로 걸음을 세는데, '정상 보행 직후 앉기'의 앉기 피크
 * 간격(≈882ms)이 정상 보행 대역(500~1000ms) 한가운데라 간격만으로는 분리할 수 없다(#131 알려진 한계).
 * 대신 이 게이트는 라벨 없이 **동적 진동 에너지의 붕괴**로 '보행→정지 전환'을 감지하고, 정지가 확인창
 * 지연 때문에 뒤늦게 '선언'되는 사이에 잘못 세어진 걸음을 **사후에 취소**한다.
 *
 * ## 신호 (오프라인 하네스 analyze_waveform.py 와 동일 기준)
 * - **3축 벡터 동적RMS**: 슬라이딩 윈도우(1s)에서 윈도우 평균 벡터(=국소 중력)를 뺀 잔차 크기의 RMS.
 *   스칼라 magnitude 의 분산은 중력이 지배해 수평 진동을 놓치므로(#131 의 바로 그 함정) 3축으로 잰다.
 * - **자기 기준 상대비**: 초기 정착 보행 구간(1~8s)의 RMS 중앙값을 기준(e_ref)으로 잡아, 말미 RMS 가
 *   `STOP_RATIO·e_ref` 미만으로 잦아들면 정지 전환. 기기·사람에 무관한 적응형 임계다.
 *
 * ## 인과적(causal) 재구성
 * 하네스의 `stop_declared_time` 은 "붕괴가 **끝까지** 지속"인지 미래를 보고 판정하는 **비인과** 함수다.
 * 실시간에는 미래가 없으므로, "붕괴가 확인창 [STOP_DETECT_LATENCY_S] 동안 **연속 지속**"으로 바꿔
 * 정지를 선언한다. 선언 시각 `T = 붕괴 온셋 + 확인창` 기준 `(T-RETRO_WINDOW_S, T]` 안 걸음을 취소한다.
 * 붕괴가 확인창을 못 채우고 회복되면 온셋을 취소한다(잠깐 잦아들었다 다시 걷는 경우 오취소 방지).
 *
 * ## 범위·한계 (정직)
 * - 오프라인 검증(24 trial 파일럿, PR#201): 소급차감이 walk_then_sit 과다를 평균적으로 흡수하나 최악
 *   +8 잔존 → **수용 기준(≤+2) 을 항상 보장하지는 않는다**. 실기기 회귀가 최종 관문이다.
 * - 검출기([WalkingStepDetectorLogic]) 는 손대지 않는다(#89/#121 크기임계 계약·회귀 테스트 유지). 이
 *   게이트는 그 위에 얹는 별도 순수 로직이며, 취소는 [WalkingSession] 이 `count - canceledSteps` 로 반영.
 * - 파라미터는 파일럿 잠정값(RETRO_WINDOW_S·STOP_DETECT_LATENCY_S 는 전량 데이터로 스윕 예정).
 *
 * 시각(timestampMs)은 검출기와 동일하게 **센서 하드웨어 표본 시각**(event.timestamp) 기준이어야 한다.
 */
class WalkStopGateLogic {

    companion object {
        const val SLIDE_WIN_S = 1.0            // 슬라이딩 윈도우 길이(초)
        const val SLIDE_HOP_S = 0.5            // 윈도우 평가 간격(초)
        const val REF_START_S = 1.0            // 기준 구간 시작(초) — 초반 정착 1s 건너뜀
        const val REF_END_S = 8.0              // 기준 구간 끝(초) — 앉기 큐 한참 전 '초기 보행'
        const val WALK_REF_MIN_DYN = 1.0       // 기준 RMS 가 이 미만이면 '애초에 보행 아님'(shuffle/sit_only 방어)
        const val STOP_RATIO = 0.4             // 말미 RMS 가 기준의 이 비율 미만이면 정지 전환
        const val STOP_DETECT_LATENCY_S = 2.0  // 붕괴 온셋→선언까지 확인 지연(초)
        const val RETRO_WINDOW_S = 3.0         // 소급차감 창 폭(초)
    }

    /** 소급차감으로 취소된 누적 걸음 수. [WalkingSession] 이 count 에서 뺀다. */
    var canceledSteps: Int = 0
        private set

    private class Sample(val t: Long, val x: Float, val y: Float, val z: Float)

    private val window = ArrayDeque<Sample>()        // 최근 SLIDE_WIN_S 구간의 원시 샘플
    private val stepTimes = ArrayDeque<Long>()        // 취소 대상이 될 수 있는 최근 걸음 시각(ms)

    private var startMs = 0L
    private var started = false
    private var lastEvalMs = Long.MIN_VALUE

    // 기준(e_ref) 수집·확정
    private val refRms = ArrayList<Double>()
    private var refFrozen = false
    private var eRef = 0.0
    private var isWalkingRef = false                  // e_ref ≥ WALK_REF_MIN_DYN (정지 전환 감지 활성)

    // 정지 온셋 추적
    private var onsetMs: Long = -1L                   // 붕괴 시작 시각(-1 = 붕괴 아님)
    private var awaitingRecovery = false              // 방금 정지 선언 → 보행 회복 전까지 재무장 보류

    /**
     * 가속도 샘플 1개를 처리한다. 정지가 선언되면 해당 소급 창의 걸음을 취소한다.
     * @return 이번 샘플에서 취소된 걸음 수(보통 0).
     */
    fun processSample(x: Float, y: Float, z: Float, timestampMs: Long): Int {
        if (!started) {
            started = true
            startMs = timestampMs
            lastEvalMs = timestampMs
        }
        window.addLast(Sample(timestampMs, x, y, z))
        val winStart = timestampMs - (SLIDE_WIN_S * 1000).toLong()
        while (window.isNotEmpty() && window.first().t < winStart) window.removeFirst()

        // 홉 간격마다만 평가(하네스 슬라이딩 홉과 동일, CPU 절약).
        if (timestampMs - lastEvalMs < (SLIDE_HOP_S * 1000).toLong()) return 0
        lastEvalMs = timestampMs
        if (window.size < 4) return 0

        val rms = windowDynamicRms()
        val elapsedS = (timestampMs - startMs) / 1000.0

        // 1) 기준 구간(1~8s) RMS 수집 → 지나가면 e_ref 확정.
        if (!refFrozen) {
            if (elapsedS in REF_START_S..REF_END_S) refRms.add(rms)
            if (elapsedS > REF_END_S) {
                refFrozen = true
                eRef = if (refRms.isNotEmpty()) median(refRms) else 0.0
                isWalkingRef = eRef >= WALK_REF_MIN_DYN
            }
            return 0 // 기준 확정 전에는 정지 판정하지 않음
        }
        if (!isWalkingRef) return 0 // 애초에 보행 아님 → 정지 전환 없음

        val thr = STOP_RATIO * eRef
        val collapsed = rms < thr

        // 2) 정지 선언 직후에는 보행이 회복(thr 이상)될 때까지 다음 온셋을 무장하지 않는다.
        if (awaitingRecovery) {
            if (!collapsed) awaitingRecovery = false
            return 0
        }

        // 3) 붕괴 온셋 추적 → 확인창 동안 연속 지속되면 정지 선언 + 소급차감.
        if (collapsed) {
            if (onsetMs < 0) onsetMs = timestampMs
            if (timestampMs - onsetMs >= (STOP_DETECT_LATENCY_S * 1000).toLong()) {
                val declaredT = onsetMs + (STOP_DETECT_LATENCY_S * 1000).toLong()
                val removed = cancelStepsInWindow(declaredT)
                canceledSteps += removed
                onsetMs = -1L
                awaitingRecovery = true
                return removed
            }
        } else {
            onsetMs = -1L // 붕괴가 확인창을 못 채우고 회복 → 온셋 취소
        }
        return 0
    }

    /** 검출기가 걸음을 카운트한 시각을 등록한다(취소 후보 버퍼). */
    fun onStepCounted(timestampMs: Long) {
        stepTimes.addLast(timestampMs)
        // 소급 창 + 확인창을 넉넉히 넘어선 과거 걸음은 더 이상 취소 대상이 아니므로 버린다(메모리 상한).
        val keepFrom = timestampMs - ((RETRO_WINDOW_S + STOP_DETECT_LATENCY_S + 1.0) * 1000).toLong()
        while (stepTimes.isNotEmpty() && stepTimes.first() < keepFrom) stepTimes.removeFirst()
    }

    fun reset() {
        canceledSteps = 0
        window.clear()
        stepTimes.clear()
        startMs = 0L
        started = false
        lastEvalMs = Long.MIN_VALUE
        refRms.clear()
        refFrozen = false
        eRef = 0.0
        isWalkingRef = false
        onsetMs = -1L
        awaitingRecovery = false
    }

    /** 현재 윈도우의 3축 벡터 동적RMS(윈도우 평균 벡터를 뺀 잔차 크기의 RMS). */
    private fun windowDynamicRms(): Double {
        val n = window.size
        var gx = 0.0; var gy = 0.0; var gz = 0.0
        for (s in window) { gx += s.x; gy += s.y; gz += s.z }
        gx /= n; gy /= n; gz /= n
        var sumSq = 0.0
        for (s in window) {
            val dx = s.x - gx; val dy = s.y - gy; val dz = s.z - gz
            sumSq += dx * dx + dy * dy + dz * dz
        }
        return sqrt(sumSq / n)
    }

    /** 정지 선언 시각 T 의 (T-RETRO_WINDOW_S, T] 안 걸음을 취소하고 그 수를 반환. */
    private fun cancelStepsInWindow(declaredT: Long): Int {
        val lo = declaredT - (RETRO_WINDOW_S * 1000).toLong()
        var removed = 0
        val it = stepTimes.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t in (lo + 1)..declaredT) { it.remove(); removed++ }
        }
        return removed
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
}

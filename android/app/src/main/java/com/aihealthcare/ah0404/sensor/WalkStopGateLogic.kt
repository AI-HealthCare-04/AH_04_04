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
 * - **자기 기준 상대비**: 초기 정착 보행 구간의 RMS 중앙값을 기준(e_ref)으로 잡아, 말미 RMS 가
 *   `STOP_RATIO·e_ref` 미만으로 잦아들면 정지 전환. 기기·사람에 무관한 적응형 임계다.
 *
 * ## 인과적(causal) 재구성 + 실사용 대응 (리뷰 #215 반영)
 * - 하네스 `stop_declared_time` 의 비인과("끝까지 붕괴")를 "붕괴가 확인창 [STOP_DETECT_LATENCY_S] 동안
 *   **연속 지속**"으로 causal 화. 선언 시각 `T` 기준 `(T-RETRO_WINDOW_S, T]` 걸음을 취소.
 * - **기준(e_ref) 앵커 = 세션 시작이 아니라 '첫 걸음(보행 확정)'**. 시작 버튼→주머니→늦은 출발로 초반이
 *   정지여도 실제 보행이 시작되면 그때부터 기준을 잡는다. 기준이 저에너지(보행 아님)면 freeze 하지 않고
 *   다음 보행 구간에서 재앵커한다(리뷰: 초반 8초 정지 시 영구 비활성 방지).
 * - **flush-on-stop**: 종료 버튼이 정지 선언 확인창(≈2s) 안에 눌리는 #131 대표 시나리오(앉으며 폰 꺼내
 *   종료)를 위해, [flushOnStop] 은 붕괴 온셋이 [MIN_ONSET_FOR_FLUSH_MS] 이상 지속 중이면 확인창을
 *   기다리지 않고 종료 시각으로 즉시 차감한다(온셋 최소 지속 조건으로 정상 보행 직후 종료의 오취소 방지).
 *
 * ## 범위·한계 (정직)
 * - 오프라인 파일럿(24 trial, PR#201): walk_then_sit 최악 +8 잔존 → **수용(≤+2) 항상 보장 X**. 실기기
 *   회귀(보행 직후 앉기 5회 ≤+2 & 정상/정지앉기/느린보행 무회귀)가 최종 관문. 파라미터는 잠정값.
 * - 정당한 일시정지(신호등 등)도 붕괴→선언이므로 소급 창의 마지막 실걸음 1~2보가 취소될 수 있다
 *   (사용자에게 유리한 과소 방향). 실기기 회귀에 '보행 중 일시정지' 시나리오로 정량화한다.
 * - 검출기([WalkingStepDetectorLogic]) 는 손대지 않는다(#89/#121 계약·회귀 테스트 유지). 취소는
 *   [WalkingSession] 이 `count - canceledSteps` 로 반영.
 *
 * 시각(timestampMs)은 검출기와 동일하게 **센서 하드웨어 표본 시각**(event.timestamp) 기준이어야 한다.
 */
class WalkStopGateLogic {

    companion object {
        const val SLIDE_WIN_S = 1.0             // 슬라이딩 윈도우 길이(초)
        const val SLIDE_HOP_S = 0.5             // 윈도우 평가 간격(초)
        const val REF_START_S = 1.0             // 기준 구간 시작(앵커=첫 걸음 이후 초) — 초반 정착 1s 건너뜀
        const val REF_END_S = 8.0               // 기준 구간 끝(앵커 이후 초)
        const val WALK_REF_MIN_DYN = 1.0        // 기준 RMS 가 이 미만이면 '보행 아님'(shuffle/sit_only 방어)
        const val STOP_RATIO = 0.4              // 말미 RMS 가 기준의 이 비율 미만이면 정지 전환
        const val STOP_DETECT_LATENCY_S = 2.0   // 붕괴 온셋→선언까지 확인 지연(초)
        const val RETRO_WINDOW_S = 3.0          // 소급차감 창 폭(초)
        const val REANCHOR_GAP_MS = 3000L       // 걸음 간격이 이보다 크면 '새 보행 구간' → 기준 재앵커
        const val MIN_ONSET_FOR_FLUSH_MS = 1000L // flush-on-stop 이 차감하려면 온셋이 최소 이만큼 지속돼야
    }

    /** 소급차감으로 취소된 누적 걸음 수. [WalkingSession] 이 count 에서 뺀다. */
    var canceledSteps: Int = 0
        private set

    private class Sample(val t: Long, val x: Float, val y: Float, val z: Float)

    private val window = ArrayDeque<Sample>()        // 최근 SLIDE_WIN_S 구간의 원시 샘플
    private val stepTimes = ArrayDeque<Long>()        // 취소 대상이 될 수 있는 최근 걸음 시각(ms)

    private var lastSampleMs = 0L
    private var lastEvalMs = Long.MIN_VALUE

    // 기준(e_ref) 앵커·수집·확정
    private var refAnchorMs = -1L                     // 기준 수집 시작 앵커 = 첫(또는 재개) 걸음 시각(-1 = 미설정)
    private val refRms = ArrayList<Double>()
    private var refFrozen = false
    private var eRef = 0.0
    private var isWalkingRef = false                  // e_ref ≥ WALK_REF_MIN_DYN (정지 전환 감지 활성)
    private var lastStepMs = -1L

    // 정지 온셋 추적
    private var onsetMs: Long = -1L                   // 붕괴 시작 시각(-1 = 붕괴 아님)
    private var awaitingRecovery = false              // 방금 정지 선언 → 보행 회복 전까지 재무장 보류

    /**
     * 가속도 샘플 1개를 처리한다. 정지가 선언되면 해당 소급 창의 걸음을 취소한다.
     * @return 이번 샘플에서 취소된 걸음 수(보통 0).
     */
    fun processSample(x: Float, y: Float, z: Float, timestampMs: Long): Int {
        lastSampleMs = timestampMs
        if (lastEvalMs == Long.MIN_VALUE) lastEvalMs = timestampMs
        window.addLast(Sample(timestampMs, x, y, z))
        val winStart = timestampMs - (SLIDE_WIN_S * 1000).toLong()
        while (window.isNotEmpty() && window.first().t < winStart) window.removeFirst()

        // 홉 간격마다만 평가(하네스 슬라이딩 홉과 동일, CPU 절약).
        if (timestampMs - lastEvalMs < (SLIDE_HOP_S * 1000).toLong()) return 0
        lastEvalMs = timestampMs
        if (window.size < 4) return 0
        if (refAnchorMs < 0) return 0 // 아직 보행 시작 전(첫 걸음이 앵커를 세운다)

        val rms = windowDynamicRms()
        val relS = (timestampMs - refAnchorMs) / 1000.0

        // 1) 앵커(첫 걸음) 기준 REF 구간에서 e_ref 수집 → 지나가면 확정.
        if (!refFrozen) {
            if (relS in REF_START_S..REF_END_S) refRms.add(rms)
            if (relS > REF_END_S) freezeRef()
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
                return declareStop(declaredT)
            }
        } else {
            onsetMs = -1L // 붕괴가 확인창을 못 채우고 회복 → 온셋 취소
        }
        return 0
    }

    /**
     * 세션 종료 시 호출. 정지 선언 확인창이 끝나기 전에 종료돼도, 붕괴 온셋이 충분히 지속 중이면
     * 종료 시각을 정지 선언 시각으로 보고 즉시 차감한다(#131 '앉으며 종료' 대표 시나리오).
     * @return 이번 flush 로 취소된 걸음 수.
     */
    fun flushOnStop(): Int {
        if (!refFrozen || !isWalkingRef || awaitingRecovery) return 0
        if (onsetMs < 0 || lastSampleMs - onsetMs < MIN_ONSET_FOR_FLUSH_MS) return 0
        return declareStop(lastSampleMs)
    }

    /** 검출기가 걸음을 카운트한 시각을 등록한다(취소 후보 버퍼 + 기준 앵커). */
    fun onStepCounted(timestampMs: Long) {
        // 기준 앵커: 첫 걸음, 또는 (아직 유효 기준 확정 전) 걸음 간격이 커 '새 보행 구간'이 시작된 경우 재앵커.
        //   → 시작 버튼 누르고 늦게 출발해도, 실제 보행이 시작되는 시점부터 기준을 잡는다.
        if (refAnchorMs < 0 || (!refFrozen && lastStepMs >= 0 && timestampMs - lastStepMs > REANCHOR_GAP_MS)) {
            refAnchorMs = timestampMs
            refRms.clear()
        }
        stepTimes.addLast(timestampMs)
        // 소급 창 + 확인창을 넉넉히 넘어선 과거 걸음은 취소 대상 아님 → 버림(메모리 상한).
        val keepFrom = timestampMs - ((RETRO_WINDOW_S + STOP_DETECT_LATENCY_S + 1.0) * 1000).toLong()
        while (stepTimes.isNotEmpty() && stepTimes.first() < keepFrom) stepTimes.removeFirst()
        lastStepMs = timestampMs
    }

    fun reset() {
        canceledSteps = 0
        window.clear()
        stepTimes.clear()
        lastSampleMs = 0L
        lastEvalMs = Long.MIN_VALUE
        refAnchorMs = -1L
        refRms.clear()
        refFrozen = false
        eRef = 0.0
        isWalkingRef = false
        lastStepMs = -1L
        onsetMs = -1L
        awaitingRecovery = false
    }

    private fun freezeRef() {
        if (refRms.isNotEmpty() && median(refRms) >= WALK_REF_MIN_DYN) {
            eRef = median(refRms)
            isWalkingRef = true
            refFrozen = true
        } else {
            // 저에너지(보행 아님) → freeze 하지 않고 다음 보행 구간에서 재앵커(onStepCounted).
            refAnchorMs = -1L
            refRms.clear()
        }
    }

    private fun declareStop(declaredT: Long): Int {
        val removed = cancelStepsInWindow(declaredT)
        canceledSteps += removed
        onsetMs = -1L
        awaitingRecovery = true
        return removed
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

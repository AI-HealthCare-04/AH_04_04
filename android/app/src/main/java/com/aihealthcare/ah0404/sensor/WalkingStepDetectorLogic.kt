package com.aihealthcare.ah0404.sensor

import kotlin.math.sqrt

/**
 * 2단계 게이팅 방식 걸음 감지 (순수 로직, 센서 없이 단위 테스트 가능).
 *
 *  1단계: 규칙적인 가속도 피크가 연속으로 감지되면 "보행 중(WALKING)"으로 판정.
 *  2단계: WALKING 상태일 때만 걸음을 카운트. 확정 순간 웜업 피크를 소급 반영(초기 걸음 누락 방지).
 *
 * 앉았다 일어서기(정지 상태) / 방향 전환 같은 일회성·느린 동작은 피크가 1~2회거나 정상 대역 밖 리듬이라
 * 진입 기준(peaksToStartWalking)에 못 미쳐 카운트되지 않는다.
 *
 * ⚠️ 알려진 한계(#89, 3차 실측): '정상 보행 직후 곧바로 앉기'는 앉기 피크 간격이 ≈882ms(68보/분)로
 *    정상 보행 대역(60~120보/분 = 500~1000ms) 안에 들어와, 간격 게이트만으로는 분리할 수 없다 →
 *    v1 미보장(과다카운트 발생). 후속 이슈 #131에서 원시 파형 기반 분리 검토. (docs/sensor_walking_gate_a4a.md §5-5)
 *
 * 감지 파라미터는 실측(#89)으로 확정돼 DEFAULT_* 로 고정된다 — A-4a 런타임 튜닝 UI(TuneRow)는 #133에서 제거.
 * var 로 두는 이유는 회귀 테스트에서 경계값·이중봉우리 버그를 재현하려 값을 바꿔 검증하기 때문이다.
 * 프로덕션 경로에는 값을 바꾸는 코드가 없으므로 항상 DEFAULT_* 로 동작한다.
 */
class WalkingStepDetectorLogic {

    enum class State { IDLE, WALKING }

    companion object {
        // 대상(팀 결정): 지팡이·보행기 없이 스스로 걷는 '독립 보행' 시니어(정상 대역 ≈60~120보/분).
        //   초저속·셔플·불규칙 보행은 v1 정확도 미보장 — 틀리면 '덜 세는 쪽'으로 열화(과다카운트=거짓 격려 금지).
        //   2차 실측(#89, 2026-07-21) 요약: 정상 20보 → 350ms에서 평균 21.0(최적). 250ms는 걸음 내부
        //   이중봉우리로 과다카운트. 정지 상태 앉았다 일어나기(느린 리듬 ≈47~52보/분)는 max 2000ms에선 걷기로
        //   오탐(실측 13·10보) → max를 정상 케이던스 대역(간격 ≤1000ms, 2차 조정 900→1000)으로 좁혀 대역 밖
        //   느린 비보행을 걸러낸다(≈1200ms 간격은 1000ms 초과라 차단).
        //   ※ 단, '보행 직후 곧바로 앉기'(≈882ms=68보/분)는 이 대역 안이라 간격으로 분리 불가 → v1 미보장(위 알려진 한계).
        // ── 실측 확정 값(#89) ─────────────────────────────────────
        const val DEFAULT_PEAK_THRESHOLD = 10.5f
        const val DEFAULT_MIN_PEAK_INTERVAL_MS = 350L // 실측 확정: 이중봉우리 병합, 진짜 걸음(≈570ms)은 유지
        const val DEFAULT_MAX_PEAK_INTERVAL_MS = 1000L // 정상 대역 상한(하한 60보/분=1000ms). 대역 밖 느린 비보행(정지 앉기 ≈1200ms)은 차단. 대역 안 '보행 직후 앉기'(≈882ms)는 분리 불가(알려진 한계)
        const val DEFAULT_PEAKS_TO_START_WALKING = 10 // 가설 유지: 연속 10걸음 확정
        const val DEFAULT_WALKING_TIMEOUT_MS = 2500L // 마지막 피크 후 이 시간 지나면 IDLE 복귀
        const val DEFAULT_LOW_PASS_ALPHA = 0.3f // 높을수록 빠른 반응 / 낮을수록 노이즈 제거
        // ──────────────────────────────────────────────────────────
    }

    // ── 감지 파라미터. 테스트에서만 값을 바꿔 검증한다(프로덕션은 DEFAULT_* 고정). reset() 해도 유지된다. ──
    var peakThreshold: Float = DEFAULT_PEAK_THRESHOLD
    var minPeakIntervalMs: Long = DEFAULT_MIN_PEAK_INTERVAL_MS
    var maxPeakIntervalMs: Long = DEFAULT_MAX_PEAK_INTERVAL_MS
    var peaksToStartWalking: Int = DEFAULT_PEAKS_TO_START_WALKING
    var walkingTimeoutMs: Long = DEFAULT_WALKING_TIMEOUT_MS
    var lowPassAlpha: Float = DEFAULT_LOW_PASS_ALPHA

    var filteredMagnitude = 9.8f
        private set
    var state = State.IDLE
        private set
    var count = 0
        private set

    /** 디버그/튜닝용: 현재까지 연속으로 감지된 규칙 피크 수 */
    var consecutivePeaks = 0
        private set

    /** 디버그/튜닝용: 마지막으로 인정된 두 피크 사이 간격(ms). 걸음 내부 이중봉우리 진단용(A-4a). */
    var lastIntervalMs: Long = 0L
        private set

    /** 디버그/튜닝용: 첫 피크~마지막 피크 구간(ms). 케이던스(보/분) 산출·측정시간 기록용. */
    val walkingSpanMs: Long
        get() = if (hasSeenPeak) lastPeakTimeMs - firstPeakTimeMs else 0L

    /** 디버그/튜닝용: 평균 케이던스(보/분). N걸음의 구간에는 간격이 N-1개이므로 (count-1)로 환산한다. */
    val cadenceStepsPerMin: Int
        get() = if (count >= 2 && walkingSpanMs > 0L) ((count - 1) * 60_000L / walkingSpanMs).toInt() else 0

    private var wasAboveThreshold = false
    private var lastPeakTimeMs = 0L
    private var firstPeakTimeMs = 0L
    private var hasSeenPeak = false

    /**
     * 가속도 샘플 1개를 처리한다.
     * @return 이번 샘플에서 걸음이 카운트됐으면 true
     */
    fun processSample(x: Float, y: Float, z: Float, timestampMs: Long): Boolean {
        val rawMagnitude = sqrt(x * x + y * y + z * z)
        filteredMagnitude =
            lowPassAlpha * rawMagnitude + (1f - lowPassAlpha) * filteredMagnitude

        // 마지막 피크 후 너무 오래 조용하면 보행 종료 → IDLE 복귀
        if (hasSeenPeak && timestampMs - lastPeakTimeMs > walkingTimeoutMs) {
            state = State.IDLE
            consecutivePeaks = 0
        }

        val isAbove = filteredMagnitude > peakThreshold
        val risingEdge = isAbove && !wasAboveThreshold
        wasAboveThreshold = isAbove

        if (!risingEdge) return false

        // 상승 에지 = 피크 후보. 중복 진동은 최소 간격으로 걸러냄.
        if (hasSeenPeak && timestampMs - lastPeakTimeMs < minPeakIntervalMs) {
            return false
        }

        val interval = if (hasSeenPeak) timestampMs - lastPeakTimeMs else Long.MAX_VALUE
        // 첫 피크는 구간 시작점만 잡고, 이후 피크는 직전 간격을 기록(진단용).
        if (hasSeenPeak) lastIntervalMs = interval else firstPeakTimeMs = timestampMs
        lastPeakTimeMs = timestampMs
        hasSeenPeak = true

        // 1단계: 규칙성 판정 (걷기다운 간격이면 연속 카운트, 아니면 리셋 후 새 시작)
        val rhythmic = interval <= maxPeakIntervalMs
        consecutivePeaks = if (rhythmic) consecutivePeaks + 1 else 1

        // 2단계: 상태별 카운트
        return when (state) {
            State.IDLE -> {
                if (consecutivePeaks >= peaksToStartWalking) {
                    // 게이트 진입: 웜업 동안 모은 피크를 소급 카운트 (걸음 누락 방지)
                    state = State.WALKING
                    count += peaksToStartWalking
                    true
                } else {
                    false // 아직 보행 확정 전 → 카운트 보류
                }
            }
            State.WALKING -> {
                if (rhythmic) {
                    count++
                    true
                } else {
                    // 리듬이 끊긴 피크(간격 > max=1000ms)는 보행 이탈로 보고 IDLE 복귀, 카운트하지 않음.
                    //   walkingTimeoutMs(정지) 외에 '대역 밖 느린 다음 피크'도 보행 종료로 처리한다.
                    //   → 대역 밖(>1000ms) 이탈만 차단할 수 있고, '보행 직후 앉기'처럼 앉기 간격이
                    //     정상 대역 안(≈882ms)이면 여기서 걸러지지 않는다(v1 알려진 한계, 리뷰 #121).
                    //   consecutivePeaks 는 위에서 1로 리셋됐으므로 이 피크는 새 웜업의 시작점이 된다(냉시작과 동일).
                    state = State.IDLE
                    false
                }
            }
        }
    }

    /** 카운트·상태만 초기화(튜닝 파라미터는 유지 — 여러 값 비교 중 계속 쓰기 위함). */
    fun reset() {
        filteredMagnitude = 9.8f
        state = State.IDLE
        count = 0
        consecutivePeaks = 0
        lastIntervalMs = 0L
        wasAboveThreshold = false
        lastPeakTimeMs = 0L
        firstPeakTimeMs = 0L
        hasSeenPeak = false
    }
}

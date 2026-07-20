package com.aihealthcare.ah0404.sensor

import kotlin.math.sqrt

/**
 * 2단계 게이팅 방식 걸음 감지 (순수 로직, 센서 없이 단위 테스트 가능).
 *
 *  1단계: 규칙적인 가속도 피크가 연속으로 감지되면 "보행 중(WALKING)"으로 판정.
 *  2단계: WALKING 상태일 때만 걸음을 카운트. 확정 순간 웜업 피크를 소급 반영(초기 걸음 누락 방지).
 *
 * 앉았다 일어서기 / 방향 전환 같은 일회성 동작은 피크가 1~2회로 끝나 진입 기준(peaksToStartWalking)에
 * 못 미쳐 카운트되지 않는다.
 *
 * ⚠️ 파라미터는 '런타임 조절 가능'(A-4a 실기기 정확도 측정용) — 앱에서 값을 바꿔가며 오탐/미탐을 잰다.
 * 기본값은 **가설값**이며 실기기 데이터로 확정 예정(팀 결정 2026-07-20). 진입 기준 가설 = 10걸음(≈8~14초).
 * 값이 확정되면 DEFAULT_* 로 고정하고 조절 UI는 정리한다.
 */
class WalkingStepDetectorLogic {

    enum class State { IDLE, WALKING }

    companion object {
        // ── 기본(가설) 값 — 실기기 측정으로 확정 예정 ─────────────
        const val DEFAULT_PEAK_THRESHOLD = 10.5f
        const val DEFAULT_MIN_PEAK_INTERVAL_MS = 250L // ≈ 최대 240보/분
        const val DEFAULT_MAX_PEAK_INTERVAL_MS = 2000L // 넘으면 리듬 끊김 → 연속 카운트 리셋
        const val DEFAULT_PEAKS_TO_START_WALKING = 10 // 가설: 연속 10걸음 확정(3→10, 오전 논의/리서치)
        const val DEFAULT_WALKING_TIMEOUT_MS = 2500L // 마지막 피크 후 이 시간 지나면 IDLE 복귀
        const val DEFAULT_LOW_PASS_ALPHA = 0.3f // 높을수록 빠른 반응 / 낮을수록 노이즈 제거
        // ──────────────────────────────────────────────────────────
    }

    // ── 런타임 조절 파라미터 (디버그 튜닝). reset() 해도 유지된다. ──
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
        consecutivePeaks = if (interval <= maxPeakIntervalMs) consecutivePeaks + 1 else 1

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
                count++
                true
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

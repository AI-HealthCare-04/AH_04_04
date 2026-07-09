package com.aihealthcare.ah0404.sensor

import kotlin.math.sqrt

/**
 * 2단계 게이팅 방식 걸음 감지 (순수 로직, 센서 없이 단위 테스트 가능).
 *
 *  1단계: 규칙적인 가속도 피크가 연속으로 감지되면 "보행 중(WALKING)"으로 판정.
 *  2단계: WALKING 상태일 때만 걸음을 카운트.
 *
 * 앉았다 일어서기 / 몸 방향 전환 같은 일회성 동작은 피크가 1~2회로 끝나
 * 보행 판정 기준(PEAKS_TO_START_WALKING)에 못 미치므로 카운트되지 않는다.
 *
 * 보행으로 확정되는 순간, 그동안 게이트 밖에서 쌓인 웜업 피크를 소급 반영해서
 * 처음 몇 걸음이 누락되지 않게 한다.
 */
class WalkingStepDetectorLogic {

    enum class State { IDLE, WALKING }

    companion object {
        // ── 조정 가능한 상수 ─────────────────────────────────────
        // 피크로 인정할 필터된 가속도 크기 (m/s²). 정지 시 중력 ≈ 9.8.
        // 낮출수록 약한 걸음도 피크로 잡음.
        const val PEAK_THRESHOLD = 10.5f

        // 피크 간 최소 간격 (ms). 이보다 짧으면 같은 걸음의 중복 진동으로 보고 무시.
        // 250ms ≈ 최대 240보/분
        const val MIN_PEAK_INTERVAL_MS = 250L

        // 피크 간 최대 간격 (ms). 이보다 길면 걷기 리듬이 끊긴 것으로 보고
        // 연속 규칙 피크 카운트를 새로 시작. (느린 걸음도 허용하도록 넉넉히)
        const val MAX_PEAK_INTERVAL_MS = 2000L

        // WALKING 상태로 진입하기 위한 연속 규칙 피크 수.
        // 낮출수록 빨리 세기 시작(웜업 짧음), 높일수록 오탐 방지가 강해짐.
        const val PEAKS_TO_START_WALKING = 3

        // 마지막 피크 후 이 시간이 지나면 보행이 멈춘 것으로 보고 IDLE 복귀.
        const val WALKING_TIMEOUT_MS = 2500L

        // 저주파 통과 필터 계수 (0~1). 높을수록 빠른 반응 / 낮을수록 노이즈 제거.
        const val LOW_PASS_ALPHA = 0.3f
        // ──────────────────────────────────────────────────────────
    }

    var filteredMagnitude = 9.8f
        private set
    var state = State.IDLE
        private set
    var count = 0
        private set

    /** 디버그/튜닝용: 현재까지 연속으로 감지된 규칙 피크 수 */
    var consecutivePeaks = 0
        private set

    private var wasAboveThreshold = false
    private var lastPeakTimeMs = 0L
    private var hasSeenPeak = false

    /**
     * 가속도 샘플 1개를 처리한다.
     * @return 이번 샘플에서 걸음이 카운트됐으면 true
     */
    fun processSample(x: Float, y: Float, z: Float, timestampMs: Long): Boolean {
        val rawMagnitude = sqrt(x * x + y * y + z * z)
        filteredMagnitude =
            LOW_PASS_ALPHA * rawMagnitude + (1f - LOW_PASS_ALPHA) * filteredMagnitude

        // 마지막 피크 후 너무 오래 조용하면 보행 종료 → IDLE 복귀
        if (hasSeenPeak && timestampMs - lastPeakTimeMs > WALKING_TIMEOUT_MS) {
            state = State.IDLE
            consecutivePeaks = 0
        }

        val isAbove = filteredMagnitude > PEAK_THRESHOLD
        val risingEdge = isAbove && !wasAboveThreshold
        wasAboveThreshold = isAbove

        if (!risingEdge) return false

        // 상승 에지 = 피크 후보. 중복 진동은 최소 간격으로 걸러냄.
        if (hasSeenPeak && timestampMs - lastPeakTimeMs < MIN_PEAK_INTERVAL_MS) {
            return false
        }

        val interval = if (hasSeenPeak) timestampMs - lastPeakTimeMs else Long.MAX_VALUE
        lastPeakTimeMs = timestampMs
        hasSeenPeak = true

        // 1단계: 규칙성 판정 (걷기다운 간격이면 연속 카운트, 아니면 리셋 후 새 시작)
        consecutivePeaks = if (interval <= MAX_PEAK_INTERVAL_MS) consecutivePeaks + 1 else 1

        // 2단계: 상태별 카운트
        return when (state) {
            State.IDLE -> {
                if (consecutivePeaks >= PEAKS_TO_START_WALKING) {
                    // 게이트 진입: 웜업 동안 모은 피크를 소급 카운트 (걸음 누락 방지)
                    state = State.WALKING
                    count += PEAKS_TO_START_WALKING
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

    fun reset() {
        filteredMagnitude = 9.8f
        state = State.IDLE
        count = 0
        consecutivePeaks = 0
        wasAboveThreshold = false
        lastPeakTimeMs = 0L
        hasSeenPeak = false
    }
}

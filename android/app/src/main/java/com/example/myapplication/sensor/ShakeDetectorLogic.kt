package com.example.myapplication.sensor

import kotlin.math.sqrt

class ShakeDetectorLogic {

    companion object {
        // 흔들기로 인식할 가속도 크기 임계값 (m/s²).
        // 정지 시 중력 ≈ 9.8이므로, 13.0 = 약 3.2 m/s² 추가 가속도.
        // 시니어의 약한 흔들기를 감지하려면 낮출수록 민감해짐.
        const val SHAKE_THRESHOLD = 13.0f

        // 같은 흔들기를 중복 카운트하지 않기 위한 최소 간격 (ms).
        // 낮출수록 빠른 연속 흔들기도 인식.
        const val MIN_SHAKE_INTERVAL_MS = 500L
    }

    // 첫 번째 샘플이 항상 감지되도록 초기값을 충분히 과거로 설정
    private var lastShakeTimeMs = -(MIN_SHAKE_INTERVAL_MS + 1L)
    var count = 0
        private set

    /**
     * 가속도 벡터 샘플을 받아 흔들기 감지 여부를 반환한다.
     * 센서 없이 임의의 값을 넣어 단위 테스트할 수 있는 순수 함수.
     *
     * @param x, y, z   가속도 값 (m/s²), SensorEvent.values[0..2]
     * @param timestampMs  현재 시각 (밀리초)
     * @return 이번 샘플이 새로운 흔들기로 카운트됐으면 true
     */
    fun processSample(x: Float, y: Float, z: Float, timestampMs: Long): Boolean {
        val magnitude = sqrt(x * x + y * y + z * z)
        val elapsed = timestampMs - lastShakeTimeMs
        return if (magnitude > SHAKE_THRESHOLD && elapsed > MIN_SHAKE_INTERVAL_MS) {
            lastShakeTimeMs = timestampMs
            count++
            true
        } else {
            false
        }
    }

    fun reset() {
        count = 0
        lastShakeTimeMs = -(MIN_SHAKE_INTERVAL_MS + 1L)
    }
}

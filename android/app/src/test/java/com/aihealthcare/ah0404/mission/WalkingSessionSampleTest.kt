package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.sensor.WalkingStepDetectorLogic
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * WalkingSession 이 실제 센서 이벤트를 detector.processSample() 로 흘려보내는 경로를
 * "합성 3축 가속도 샘플"로 검증한다(백엔드/기기 불필요).
 *
 * 기존 WalkingStepDetectorLogicTest 는 z축만 썼지만, 실제 가속도계는 x/y/z 에 분산된다.
 * 여기서는 같은 크기(magnitude)를 세 축에 고루 나눈 샘플로도 걸음이 정상 집계되는지 확인한다.
 * (WalkingSession 자체는 SensorManager 의존이라 계측 테스트 영역이므로, 순수 로직 경로만 검증)
 */
class WalkingSessionSampleTest {

    private lateinit var detector: WalkingStepDetectorLogic

    @Before
    fun setUp() {
        detector = WalkingStepDetectorLogic()
    }

    // 주어진 크기(magnitude)를 x=y=z 에 고루 분산: sqrt(3·v²)=mag → v=mag/√3
    private fun axis(mag: Float): Float = mag / sqrt(3f)

    // 한 걸음 = 피크 샘플(큰 크기) + 골 샘플(작은 크기). 시각은 피크 기준.
    private fun oneStep(peakMs: Long) {
        val hi = axis(18f)
        val lo = axis(2f)
        detector.processSample(hi, hi, hi, peakMs)         // 상승 → 피크
        detector.processSample(lo, lo, lo, peakMs + 120L)  // 하강 → 임계값 아래
    }

    private fun walkSteps(count: Int, startMs: Long = 0L, intervalMs: Long = 600L) {
        for (i in 0 until count) oneStep(startMs + i * intervalMs)
    }

    @Test
    fun `3축 분산 샘플로도 연속 10걸음이면 정확히 10 집계`() {
        walkSteps(10)
        assertEquals(10, detector.count)
        assertEquals(WalkingStepDetectorLogic.State.WALKING, detector.state)
    }

    @Test
    fun `분산 샘플에서도 3걸음 웜업 소급 카운트`() {
        walkSteps(3)
        assertEquals(3, detector.count)
    }

    @Test
    fun `일회성 흔들림(피크 1회)은 걸음으로 집계되지 않음`() {
        oneStep(0L)
        assertEquals(0, detector.count)
        assertEquals(WalkingStepDetectorLogic.State.IDLE, detector.state)
    }

    @Test
    fun `충분히 긴 보행(30걸음)도 정확히 집계`() {
        walkSteps(30)
        assertEquals(30, detector.count)
    }

    @Test
    fun `세션 중 긴 공백(일시정지) 후 재보행은 재웜업이 필요`() {
        walkSteps(5)                       // WALKING, count 5
        // 공백: 다음 피크가 TIMEOUT(2500ms) 이후 → IDLE 복귀 후 재시작
        walkSteps(3, startMs = 5_000L)     // 재웜업 3걸음 → 다시 +3
        assertEquals(8, detector.count)
    }
}

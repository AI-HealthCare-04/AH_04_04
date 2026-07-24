package com.aihealthcare.ah0404.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * #131 적응형 정지판정 게이트(WalkStopGateLogic) 단위 테스트.
 *
 * 파형 분석 findings §4 의 채택안(적응형 r2)을 **결정론적 합성 3축 신호**로 고정한다:
 *   - 보행 = 지속되는 수평 진동(동적RMS 높음)
 *   - 앉기 = 진동이 잦아든 정착 구간(동적RMS 낮음)
 * 핵심 검증: ① 정상 보행은 절대 정지로 안 뜬다(과소계수 위험 0) ② 보행 직후 앉기는 정지로 잡힌다
 * ③ 보행 수준 미달 저에너지는 정지판정 자체를 안 한다 ④ 재보행하면 정지가 해제된다.
 */
class WalkStopGateLogicTest {

    private lateinit var gate: WalkStopGateLogic
    private var everStopped = false

    @Before
    fun setUp() {
        gate = WalkStopGateLogic()
        everStopped = false
    }

    private val hz = 50
    private val dtMs = 20L

    /** `secs` 초 동안 수평 진폭 `amp` 의 진동을 50Hz 로 흘려보낸다(중력 z=9.8). 다음 시작시각 반환. */
    private fun feed(startMs: Long, secs: Double, amp: Float, fHz: Float = 2f): Long {
        val n = (secs * hz).toInt()
        var t = startMs
        for (i in 0 until n) {
            val ph = 2.0 * PI * fHz * i / hz
            val dx = amp * sin(ph).toFloat()
            val dy = amp * cos(ph).toFloat()
            gate.processSample(dx, dy, 9.8f, t)
            if (gate.isStopped) everStopped = true
            t += dtMs
        }
        return t
    }

    @Test
    fun `정상 보행은 20초 내내 정지로 뜨지 않는다(과소계수 위험 0)`() {
        feed(startMs = 0L, secs = 20.0, amp = 2.5f)
        assertFalse("연속 보행은 말미 에너지가 기준선과 비슷해 정지로 뜨면 안 된다", everStopped)
    }

    @Test
    fun `보행 직후 앉기는 정지로 잡힌다`() {
        var t = feed(startMs = 0L, secs = 15.0, amp = 2.5f) // 보행
        assertFalse("아직 보행 중이면 정지 아님", gate.isStopped)
        everStopped = false
        feed(startMs = t, secs = 3.0, amp = 0.2f)           // 앉기(에너지 붕괴)
        assertTrue("보행 직후 앉기는 기준선 대비 말미 붕괴로 정지 포착되어야 한다", everStopped)
    }

    @Test
    fun `보행 수준 미달 저에너지(서있기 미세진동)는 정지판정을 하지 않는다`() {
        feed(startMs = 0L, secs = 20.0, amp = 0.3f) // 기준선 < WALK_REF_MIN_DYN(1.0)
        assertFalse("애초에 보행이 아니면(was_walking=false) 정지로 뜨지 않는다", everStopped)
    }

    @Test
    fun `웜업 구간(기준선 표본 부족)에는 정지판정을 보류한다`() {
        feed(startMs = 0L, secs = 3.0, amp = 2.5f) // baseline 윈도우 부족
        assertFalse("기준선/말미 표본이 모이기 전(<약 7초)에는 정지판정 보류", gate.isStopped)
    }

    @Test
    fun `앉았다가 다시 걸으면 정지가 해제된다(재보행 카운트 복귀)`() {
        var t = feed(startMs = 0L, secs = 15.0, amp = 2.5f) // 보행
        t = feed(startMs = t, secs = 3.0, amp = 0.2f)       // 앉기 → 정지
        assertTrue("앉기 직후에는 정지 상태여야 한다", gate.isStopped)
        feed(startMs = t, secs = 8.0, amp = 2.5f)           // 재보행
        assertFalse("재보행하면 말미 에너지가 되살아나 정지 해제(다시 카운트 가능)", gate.isStopped)
    }

    @Test
    fun `장시간 앉아 있어도 재보행 없이는 동결이 유지된다(기준선 latch)`() {
        var t = feed(startMs = 0L, secs = 15.0, amp = 2.5f) // 보행
        t = feed(startMs = t, secs = 2.5, amp = 0.15f)      // 앉기 → 정지 진입
        assertTrue("앉기 직후 정지 진입", gate.isStopped)
        // 8초 더 앉아 있음(누적 10초+ = baseline 구간까지 저에너지가 차는 7초 경계를 넘김).
        //   기준선이 latch 안 되면 여기서 b<walkRefMinDyn 이 되어 재보행 없이 풀린다(리뷰 블로커).
        for (k in 1..8) {
            t = feed(startMs = t, secs = 1.0, amp = 0.15f)
            assertTrue("앉은 지 ${2.5 + k}s 경과에도 재보행 없이 동결이 유지되어야 한다(latch)", gate.isStopped)
        }
        feed(startMs = t, secs = 8.0, amp = 2.5f)           // 실제 재보행
        assertFalse("실제 재보행일 때만 동결 해제", gate.isStopped)
    }
}

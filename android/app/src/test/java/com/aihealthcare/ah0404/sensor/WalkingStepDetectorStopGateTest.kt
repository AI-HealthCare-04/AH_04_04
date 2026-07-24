package com.aihealthcare.ah0404.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * #131 통합: WalkingStepDetectorLogic 에 붙인 적응형 정지판정 게이트(WalkStopGateLogic)가
 * 감지기에 올바르게 배선됐는지 dense 50Hz 합성 신호로 검증한다.
 *
 * 검증하는 두 불변식:
 *  A) **과소계수 0** — 정상 보행에서는 게이트 on/off 카운트가 동일해야 한다(진짜 걸음을 절대 안 끊음).
 *  B) **동결 배선** — 보행 직후 앉기(지속 에너지 붕괴)에서는 감지기가 카운트 동결 상태(isCountingFrozen)로
 *     전환돼야 한다(앉기 구간의 대역 안 피크를 카운트에서 막는다).
 *
 * 참고: '보행 직후 앉기'의 **실제 과다카운트 수치 억제**는 in-band 피크와 에너지 붕괴가 공존하는
 * 실측 파형이라야 온전히 재현된다(합성으로는 피크/에너지 결합이 부정확). 그래서 수치 억제의 최종 수용은
 * 실측/온디바이스 회귀로 확인한다(docs/sensor_waveform_pilot_findings.md §7 수용 기준). 여기서는 배선을 고정한다.
 */
class WalkingStepDetectorStopGateTest {

    private val hz = 50
    private val dtMs = 20L

    /** 지속 보행: 수직 바운스(피크 유발) + 수평 스윙(동적에너지). 걸음 ≈stepHz. */
    private fun feedWalk(logic: WalkingStepDetectorLogic, startMs: Long, secs: Double,
                         stepHz: Float = 1.7f, vert: Float = 2.3f, horiz: Float = 1.5f): Long {
        val n = (secs * hz).toInt()
        var t = startMs
        for (i in 0 until n) {
            val ph = 2.0 * PI * stepHz * i / hz
            val z = 9.8f + vert * sin(ph).toFloat()
            val x = horiz * sin(ph).toFloat()
            val y = horiz * cos(ph).toFloat()
            logic.processSample(x, y, z, t)
            t += dtMs
        }
        return t
    }

    /** 앉기(지속 에너지 붕괴): dense 50Hz 저진폭 미세진동. 보행 기준선 대비 말미가 무너져 게이트가 정지로 잡는다. */
    private fun feedSettle(logic: WalkingStepDetectorLogic, startMs: Long, secs: Double, amp: Float = 0.15f): Long {
        val n = (secs * hz).toInt()
        var t = startMs
        for (i in 0 until n) {
            val ph = 2.0 * PI * 1.0 * i / hz
            logic.processSample(amp * sin(ph).toFloat(), amp * cos(ph).toFloat(), 9.8f, t)
            t += dtMs
        }
        return t
    }

    @Test
    fun `정상 보행은 게이트 on-off 카운트가 동일하다(과소계수 0)`() {
        val off = WalkingStepDetectorLogic().also { it.stopGateEnabled = false }
        val on = WalkingStepDetectorLogic().also { it.stopGateEnabled = true }
        feedWalk(off, 0L, 20.0)
        feedWalk(on, 0L, 20.0)
        assertTrue("합성 보행이 걸음을 실제로 세야 유의미하다(count=${off.count})", off.count >= 15)
        assertEquals("정상 보행에서 게이트가 진짜 걸음을 끊으면 안 된다", off.count, on.count)
    }

    @Test
    fun `보행 직후 앉기(에너지 붕괴)에서 감지기가 카운트 동결 상태가 된다`() {
        val logic = WalkingStepDetectorLogic() // 게이트 기본 on
        val t = feedWalk(logic, 0L, 12.0)
        assertTrue("합성 보행이 걸음을 세야 한다(count=${logic.count})", logic.count > 0)
        assertFalse("보행 중에는 동결 아님", logic.isCountingFrozen)

        feedSettle(logic, t, 3.0) // 앉기: 지속 에너지 붕괴
        assertTrue("보행 직후 앉기(기준선 대비 말미 붕괴)면 카운트 동결 상태여야 한다", logic.isCountingFrozen)
    }

    @Test
    fun `앉았다 다시 걸으면 동결이 해제된다`() {
        val logic = WalkingStepDetectorLogic()
        var t = feedWalk(logic, 0L, 12.0)
        t = feedSettle(logic, t, 3.0)
        assertTrue("앉기 직후 동결", logic.isCountingFrozen)
        feedWalk(logic, t, 8.0) // 재보행
        assertFalse("재보행하면 말미 에너지가 되살아나 동결 해제(다시 카운트 가능)", logic.isCountingFrozen)
    }
}

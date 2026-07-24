package com.aihealthcare.ah0404.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * #131 통합: WalkingStepDetectorLogic 에 붙인 적응형 정지판정 게이트(WalkStopGateLogic)가
 * 감지기에 올바르게 배선돼 **'보행 직후 앉기' 과다카운트를 억제**하는지 dense 50Hz 합성 신호로 검증한다.
 *
 * 신호 모델:
 *   - 보행 = 연속 수평 진동(지속 동적에너지) + 스텝당 짧은 수직 z-펄스(피크 발화). state=WALKING 유지.
 *   - 앉기 = 대부분 조용(에너지 붕괴) + 대역 안(≈882ms) 간헐 임팩트 z-펄스(=#89 가 간격으로 못 거르는 그것).
 *
 * 검증:
 *   A) 과소계수 0 — 정상 보행은 게이트 on/off 카운트 동일(진짜 걸음 안 끊음).
 *   B) 동결 배선 — 보행 후 앉기 에너지 붕괴 시 isCountingFrozen 전환.
 *   C) 재보행 해제 — 다시 걸으면 동결 풀림.
 *   D) 과다카운트 억제 — 게이트 없으면 앉기 임팩트가 계속 세어져 크게 과다(≈+10), 게이트가 에너지 붕괴를
 *      감지해 동결하면 잔여는 '2s 감지지연' 동안의 임팩트 소수(≤3)로 줄어든다.
 *
 * 참고: 잔여(≤3)는 findings §4 의 2초 정착창 감지지연이다. 전환 타이밍이 자연스러우면(임팩트가 보행 종료와
 * 정확히 겹치지 않으면) ≤2 로 §7 수용기준을 만족하지만, 여기서는 '임팩트가 전환 순간과 겹치는' 최악을 보수적으로
 * 고정한다. 실제 파형에서의 최종 수용(≤+2)·파라미터(k·창) 튜닝은 본 수집 회귀로 확인한다(findings §7).
 */
class WalkingStepDetectorStopGateTest {

    private val hz = 50
    private val dtMs = 20L
    private val stepSamples = (hz / 1.7f).roundToInt()

    /** 지속 보행: 연속 수평 진동(에너지) + 스텝당 z-펄스(피크). state=WALKING 유지. */
    private fun feedWalk(logic: WalkingStepDetectorLogic, startMs: Long, secs: Double): Long {
        val n = (secs * hz).toInt()
        var t = startMs
        for (i in 0 until n) {
            val ph = 2.0 * PI * 1.7 * i / hz
            val z = if (i % stepSamples < 3) 12.8f else 9.8f // 스텝당 60ms z-펄스 → 임계 상향돌파
            logic.processSample((2.0 * sin(ph)).toFloat(), (2.0 * cos(ph)).toFloat(), z, t)
            t += dtMs
        }
        return t
    }

    /** 앉기(지속 에너지 붕괴): dense 50Hz 저진폭 미세진동, 임팩트 없음. 게이트가 정지로 잡아야 한다. */
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

    /** 앉기(대역 안 임팩트): 대부분 조용 + ≈impactMs 마다 짧은 z-펄스. 반환 = 앉기 구간에서 카운트된 걸음 수. */
    private fun feedSitWithImpacts(logic: WalkingStepDetectorLogic, startMs: Long, secs: Double,
                                   impactMs: Long = 882L): Int {
        val n = (secs * hz).toInt()
        var t = startMs
        var next = startMs
        var since = Int.MAX_VALUE
        var counted = 0
        for (i in 0 until n) {
            if (t >= next) { next += impactMs; since = 0 }
            val z = if (since < 3) 12.8f else 9.8f
            since++
            if (logic.processSample(0.1f, 0.1f, z, t)) counted++
            t += dtMs
        }
        return counted
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

        feedSettle(logic, t, 3.0)
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

    @Test
    fun `보행 직후 앉기 대역 안 임팩트 과다카운트를 게이트가 크게 억제한다`() {
        val off = WalkingStepDetectorLogic().also { it.stopGateEnabled = false }
        val on = WalkingStepDetectorLogic().also { it.stopGateEnabled = true }
        val offSit = feedSitWithImpacts(off, feedWalk(off, 0L, 15.0), 8.0)
        val onSit = feedSitWithImpacts(on, feedWalk(on, 0L, 15.0), 8.0)
        // 게이트 없으면 앉기 임팩트(간격 ≈882ms, 정상 대역 안)가 계속 세어져 크게 과다 — #89 §5-5 그대로 재현.
        assertTrue("dense 앉기 과다카운트가 재현되어야 한다(offSit=$offSit)", offSit >= 8)
        // 게이트가 에너지 붕괴를 감지해 동결 → 잔여는 2초 정착창 감지지연 동안의 임팩트 소수뿐.
        assertTrue("게이트가 앉기 과다카운트를 소수로 억제해야 한다(onSit=$onSit)", onSit <= 3)
        assertTrue("억제폭이 커야 한다(offSit=$offSit, onSit=$onSit)", offSit - onSit >= 6)
    }
}

package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.StsAssessmentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 5STS 추이 순수 로직(#353) — 개선=감소(점수와 방향 반대) 문구 반전과 표시 포맷 고정.
 * 비의료(#57): 문구는 빨라졌/느려졌(사실)까지만 — 판정 표현 없음.
 */
class StsTrendTest {

    private fun item(id: Int, sec: Double, iso: String) = StsAssessmentItem(
        physicalAssessmentId = id,
        assessmentType = "reassessment",
        chairStand5TimeSec = sec,
        createdAt = iso,
    )

    @Test
    fun `초 표시 - 소수 1자리`() {
        assertEquals("10.8초", stsSecondsLabel(10.8))
        assertEquals("12.5초", stsSecondsLabel(12.49))
        assertEquals("9.0초", stsSecondsLabel(9.0))
    }

    @Test
    fun `변화 문구 - 감소가 개선(빨라졌어요)`() {
        assertEquals("지난번보다 1.7초 빨라졌어요", stsChangeLine(previousSec = 12.5, latestSec = 10.8))
        assertEquals("지난번보다 2.0초 느려졌어요", stsChangeLine(previousSec = 10.0, latestSec = 12.0))
    }

    @Test
    fun `변화 문구 - 근소 차이는 비슷해요`() {
        assertEquals("지난번과 비슷해요", stsChangeLine(previousSec = 10.80, latestSec = 10.84))
    }

    @Test
    fun `변화 문구 - 측정 2회 미만이면 없음`() {
        assertNull(stsChangeLine(previousSec = null, latestSec = 10.8))
        assertNull(stsChangeLine(previousSec = 10.8, latestSec = null))
    }

    @Test
    fun `최근 측정 행 - MMdd·초 포맷, 최대 5개`() {
        val items = (1..7).map { i -> item(i, 10.0 + i, "2026-07-${20 + i}T10:00:00+09:00") }
        val lines = stsRecentLines(items)
        assertEquals(5, lines.size)
        assertEquals("07.21 · 11.0초", lines[0])
    }
}

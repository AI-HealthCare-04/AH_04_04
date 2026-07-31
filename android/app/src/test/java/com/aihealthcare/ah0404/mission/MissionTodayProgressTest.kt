package com.aihealthcare.ah0404.mission

import com.aihealthcare.ah0404.network.MissionTodayProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 미션 목록 카드의 '오늘 누적 진행' 표시 순수 로직 테스트(운동·걷기 today_progress).
 *  - 분 포맷: 목표 미달은 버림(9.x 가 10 으로 안 보이게), 달성은 반올림.
 *  - 한 줄 문구: 걷기는 걸음 곁들임, 달성 시 축하 문구.
 *  - 진행바 비율: 목표 대비 0~1 로 클램프.
 */
class MissionTodayProgressTest {

    @Test
    fun `목표 미달 분은 버림해 9점대가 10으로 보이지 않는다`() {
        assertEquals("9.5", formatTodayProgressMinutes(9.5f, goalReached = false))
        assertEquals("9.9", formatTodayProgressMinutes(9.96f, goalReached = false))
        assertEquals("4", formatTodayProgressMinutes(4.0f, goalReached = false))
    }

    @Test
    fun `목표 달성 분은 반올림해 자연스럽게 보여준다`() {
        assertEquals("10", formatTodayProgressMinutes(10.0f, goalReached = true))
        assertEquals("12", formatTodayProgressMinutes(12.0f, goalReached = true))
        // 부동소수 오차로 9.9f 가 9.8 로 내려가지 않아야 한다(1e-3 보정).
        assertEquals("6.9", formatTodayProgressMinutes(6.9f, goalReached = false))
    }

    @Test
    fun `미달 운동은 오늘까지 N분 하셨어요 문구`() {
        val line = todayProgressLine(MissionTodayProgress(totalMin = 4.0f, totalSteps = null, goalReached = false))
        assertEquals("오늘까지 4분 하셨어요", line)
    }

    @Test
    fun `달성이면 축하 문구로 바뀐다`() {
        val line = todayProgressLine(MissionTodayProgress(totalMin = 10.0f, totalSteps = null, goalReached = true))
        assertEquals("오늘 목표를 채웠어요 🎉 · 10분", line)
    }

    @Test
    fun `걷기는 걸음 누적을 천단위 구분해 곁들인다`() {
        val line = todayProgressLine(MissionTodayProgress(totalMin = 12.0f, totalSteps = 1500, goalReached = false))
        assertEquals("오늘까지 12분 · 1,500걸음 하셨어요", line)
    }

    @Test
    fun `걸음이 0이거나 null이면 걸음 문구를 붙이지 않는다`() {
        assertEquals(
            "오늘까지 3분 하셨어요",
            todayProgressLine(MissionTodayProgress(totalMin = 3.0f, totalSteps = 0, goalReached = false)),
        )
        assertEquals(
            "오늘까지 3분 하셨어요",
            todayProgressLine(MissionTodayProgress(totalMin = 3.0f, totalSteps = null, goalReached = false)),
        )
    }

    @Test
    fun `진행바 비율은 목표 대비 0에서 1로 클램프된다`() {
        val p = MissionTodayProgress(totalMin = 5.0f, totalSteps = null, goalReached = false)
        assertEquals(0.5f, todayProgressFraction(p, targetValue = 10), 1e-4f)
        // 목표 초과여도 1 을 넘지 않는다.
        val over = MissionTodayProgress(totalMin = 15.0f, totalSteps = null, goalReached = true)
        assertEquals(1.0f, todayProgressFraction(over, targetValue = 10), 1e-4f)
        // 방어: 목표 0 이하면 0.
        assertEquals(0.0f, todayProgressFraction(p, targetValue = 0), 1e-4f)
    }
}

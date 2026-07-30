package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.MissionLogItem
import org.junit.Assert.assertEquals
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Test

/** 나의 기록 차트(#기록탭 §5) 순수 집계 헬퍼 검증. */
class RecordChartsTest {

    private val kst: TimeZone = TimeZone.getTimeZone("Asia/Seoul")

    private fun log(day: String, success: Boolean, title: String = "미션", type: String = "walking") =
        MissionLogItem(
            missionLogId = day.hashCode(),
            missionType = type,
            title = title,
            completedAt = "${day}T10:12:00+09:00",
            success = success,
            countedForDaily = success,
            earnedPoints = if (success) 10 else 0,
        )

    @Test
    fun recentDateKeys_returns_days_ending_today() {
        val today = GregorianCalendar(kst).apply { clear(); set(2026, Calendar.JULY, 30, 12, 0, 0) }.timeInMillis
        assertEquals(listOf("2026-07-28", "2026-07-29", "2026-07-30"), recentDateKeys(3, today))
    }

    @Test
    fun dailyCompletionCounts_counts_only_success_by_completed_date() {
        val logs = listOf(
            log("2026-07-29", true),
            log("2026-07-30", true),
            log("2026-07-30", true),
            log("2026-07-30", false), // 실패는 제외
        )
        val keys = listOf("2026-07-28", "2026-07-29", "2026-07-30")
        assertEquals(listOf(0, 1, 2), dailyCompletionCounts(logs, keys))
    }

    @Test
    fun successMissionsOn_filters_and_sorts_by_time() {
        val logs = listOf(
            log("2026-07-30", true, title = "가볍게 걷기"),
            log("2026-07-29", true, title = "다른날"),
            log("2026-07-30", false, title = "실패"),
        )
        val result = successMissionsOn(logs, "2026-07-30")
        assertEquals(1, result.size)
        assertEquals("가볍게 걷기", result.single().title)
    }

    @Test
    fun koreanTime_formats_am_pm() {
        assertEquals("오전 10:12", koreanTime("2026-07-30T10:12:00+09:00"))
        assertEquals("오후 1:05", koreanTime("2026-07-30T13:05:00+09:00"))
        assertEquals("오전 12:30", koreanTime("2026-07-30T00:30:00+09:00"))
        assertEquals("오후 12:00", koreanTime("2026-07-30T12:00:00+09:00"))
        assertEquals("", koreanTime(null))
    }

    @Test
    fun stampEmoji_maps_daily_result() {
        assertEquals("🏆", stampEmoji("great_success"))
        assertEquals("⭐", stampEmoji("success"))
        assertEquals("", stampEmoji("none"))
        assertEquals("", stampEmoji(null))
    }
}

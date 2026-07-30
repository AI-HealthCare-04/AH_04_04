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

    // counted 를 success 와 별도로 줄 수 있게 한다: 목표를 넘긴 뒤의 추가 세션은 success=true·counted=false 다.
    private fun log(
        day: String,
        success: Boolean,
        title: String = "미션",
        type: String = "walking",
        counted: Boolean = success,
    ) = MissionLogItem(
        missionLogId = (day + title).hashCode(),
        missionType = type,
        title = title,
        completedAt = "${day}T10:12:00+09:00",
        success = success,
        countedForDaily = counted,
        earnedPoints = if (counted) 10 else 0,
    )

    @Test
    fun recentDateKeys_returns_days_ending_today() {
        val today = GregorianCalendar(kst).apply { clear(); set(2026, Calendar.JULY, 30, 12, 0, 0) }.timeInMillis
        assertEquals(listOf("2026-07-28", "2026-07-29", "2026-07-30"), recentDateKeys(3, today))
    }

    @Test
    fun dailyCompletionCounts_counts_counted_for_daily_not_extra_success_sessions() {
        val logs = listOf(
            log("2026-07-29", success = true), // counted
            log("2026-07-30", success = true, title = "첫 달성"), // counted
            log("2026-07-30", success = true, title = "추가 세션", counted = false), // 목표 넘긴 뒤(성공·미적립) → 제외
            log("2026-07-30", success = false, title = "실패"), // 제외
        )
        val keys = listOf("2026-07-28", "2026-07-29", "2026-07-30")
        // 7/30 은 1 — 홈 완료 개수·포인트와 동일 기준(추가 성공 세션은 완료로 안 셈).
        assertEquals(listOf(0, 1, 1), dailyCompletionCounts(logs, keys))
    }

    @Test
    fun completedMissionsOn_filters_counted_and_sorts_by_time() {
        val logs = listOf(
            log("2026-07-30", success = true, title = "가볍게 걷기"),
            log("2026-07-30", success = true, title = "추가 걷기", counted = false), // 성공·미적립 → 제외
            log("2026-07-29", success = true, title = "다른날"),
            log("2026-07-30", success = false, title = "실패"),
        )
        val result = completedMissionsOn(logs, "2026-07-30")
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

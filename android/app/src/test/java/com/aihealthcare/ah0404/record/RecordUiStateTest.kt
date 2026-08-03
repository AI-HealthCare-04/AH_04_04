package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.MissionLogItem
import com.aihealthcare.ah0404.network.WalkingDayPoint
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 기록 화면 표시 모델 파생 로직(#387) — 화면이 아니라 **집계 규칙**을 고정한다.
 *  화면에 숫자가 잘못 나오는 사고는 대부분 여기서 갈리므로 순수 함수로 떼어 테스트한다.
 */
class RecordUiStateTest {

    private val kst: TimeZone = TimeZone.getTimeZone("Asia/Seoul")

    /** 2026-08-03 12:00 KST. */
    private val today: Long = GregorianCalendar(kst).apply {
        clear(); set(2026, Calendar.AUGUST, 3, 12, 0, 0)
    }.timeInMillis

    private fun log(date: String, type: String, counted: Boolean = true) = MissionLogItem(
        missionLogId = 1,
        missionType = type,
        title = type,
        completedAt = "${date}T09:00:00+09:00",
        success = true,
        countedForDaily = counted,
        earnedPoints = 10,
    )

    // ── 이번 달 요약 ─────────────────────────────────────────────────────────

    @Test
    fun joinedDays_is_success_plus_great_success() {
        val stamps = mapOf(
            "2026-08-01" to "success",
            "2026-08-02" to "great_success",
            "2026-08-03" to "success",
            "2026-08-04" to "none", // 활동은 있었지만 목표 미달 → 참여로 세지 않는다
        )
        val summary = monthSummaryOf(stamps)
        assertEquals(3, summary.joinedDays)
        assertEquals(2, summary.successCount)
        assertEquals(1, summary.greatSuccessCount)
    }

    @Test
    fun monthSummary_is_zero_when_no_stamps() {
        val summary = monthSummaryOf(emptyMap())
        assertEquals(0, summary.joinedDays)
        assertEquals(0, summary.successCount)
        assertEquals(0, summary.greatSuccessCount)
    }

    // ── 달력 ─────────────────────────────────────────────────────────────────

    @Test
    fun dayMarks_covers_month_and_flags_today() {
        val marks = dayMarksOf(2026, 8, mapOf("2026-08-05" to "great_success"), today)
        assertEquals(31, marks.size)
        assertEquals(DayResult.GREAT_SUCCESS, marks[4].result)
        assertEquals(DayResult.NONE, marks[0].result)
        assertTrue(marks[2].isToday) // 8/3
        assertFalse(marks[3].isToday)
    }

    @Test
    fun dayMarks_has_no_today_in_other_month() {
        val marks = dayMarksOf(2026, 7, emptyMap(), today)
        assertEquals(31, marks.size)
        assertTrue(marks.none { it.isToday })
    }

    // ── 최근 7일 걷기 ────────────────────────────────────────────────────────

    @Test
    fun walk7d_always_has_seven_slots_with_null_for_missing_days() {
        val points = walk7dOf(
            listOf(WalkingDayPoint(date = "2026-08-03", steps = 3000, minutes = 25.0)),
            WalkUnit.MINUTES,
            today,
        )
        assertEquals(7, points.size)
        assertEquals(25, points.last().value) // 오늘
        assertEquals(null, points.first().value) // 7일 전 기록 없음
    }

    @Test
    fun walk7d_switches_between_minutes_and_steps() {
        val days = listOf(WalkingDayPoint(date = "2026-08-03", steps = 3000, minutes = 25.0))
        assertEquals(25, walk7dOf(days, WalkUnit.MINUTES, today).last().value)
        assertEquals(3000, walk7dOf(days, WalkUnit.STEPS, today).last().value)
    }

    @Test
    fun walk_is_empty_when_all_values_missing_or_zero() {
        assertTrue(isWalkEmpty(walk7dOf(emptyList(), WalkUnit.MINUTES, today)))
        val zeroOnly = listOf(WalkingDayPoint(date = "2026-08-03", steps = 0, minutes = 0.0))
        assertTrue(isWalkEmpty(walk7dOf(zeroOnly, WalkUnit.MINUTES, today)))
        val some = listOf(WalkingDayPoint(date = "2026-08-03", steps = 10, minutes = 1.0))
        assertFalse(isWalkEmpty(walk7dOf(some, WalkUnit.MINUTES, today)))
    }

    // ── 미션별 완료 현황 ─────────────────────────────────────────────────────

    @Test
    fun missionProgress_counts_days_not_logs() {
        val logs = listOf(
            log("2026-08-03", "walking"),
            log("2026-08-03", "walking"),
            log("2026-08-02", "walking"),
        )
        val walk = missionProgressOf(logs, today).first { it.type == MissionKind.WALK }
        assertEquals(2, walk.done)
        assertEquals(7, walk.total)
    }

    @Test
    fun missionProgress_ignores_logs_outside_seven_day_window() {
        val logs = listOf(
            log("2026-08-03", "meal"), // 오늘 — 포함
            log("2026-07-28", "meal"), // 7일 창(07-28~08-03) 시작일 — 포함
            log("2026-07-27", "meal"), // 창 밖 — 제외
        )
        val meal = missionProgressOf(logs, today).first { it.type == MissionKind.MEAL }
        assertEquals(2, meal.done)
    }

    @Test
    fun missionProgress_ignores_uncounted_logs() {
        // 홈 화면의 완료 개수·포인트와 같은 기준(countedForDaily)이라야 두 화면 숫자가 어긋나지 않는다.
        val logs = listOf(log("2026-08-03", "exercise", counted = false))
        val exercise = missionProgressOf(logs, today).first { it.type == MissionKind.EXERCISE }
        assertEquals(0, exercise.done)
    }

    @Test
    fun missionProgress_keeps_fixed_type_order() {
        val types = missionProgressOf(emptyList(), today).map { it.type }
        assertEquals(
            listOf(MissionKind.WALK, MissionKind.EXERCISE, MissionKind.MEAL, MissionKind.GAME),
            types,
        )
    }

    // ── 기준일 라벨 ──────────────────────────────────────────────────────────

    @Test
    fun measuredAtLabel_formats_or_hides() {
        assertEquals("2026.08.02 기준", measuredAtLabel("2026-08-02T10:00:00+09:00"))
        assertEquals(null, measuredAtLabel("2026-08"))
        assertEquals(null, measuredAtLabel(null))
    }
}

/**
 * 점수 빈 상태에서 **어떤 카드까지 보여줄지** 고정한다 — PR #413 리뷰 P1.
 *  연령 대상 밖(65세 미만)에 "검사하면 또래 위치를 확인할 수 있다"고 약속하면 지킬 수 없는 안내가 된다.
 */
class ScoreEmptyCardsTest {

    @Test
    fun cohort_empty_card_only_for_pending() {
        assertTrue(showsCohortEmptyCard(ScoreEmptyState.PENDING))
        assertFalse(showsCohortEmptyCard(ScoreEmptyState.UNDER_AGE))
        assertFalse(showsCohortEmptyCard(ScoreEmptyState.PREPARING))
    }

    @Test
    fun age_branches_map_to_expected_empty_state() {
        // 대상 밖 두 구간은 또래 위치 카드를 얻지 못한다(위 규칙과 함께 봐야 계약이 완성된다).
        assertFalse(showsCohortEmptyCard(scoreEmptyState(40)))  // 학습 데이터 없음
        assertFalse(showsCohortEmptyCard(scoreEmptyState(60)))  // 새 모델 준비 중
        assertTrue(showsCohortEmptyCard(scoreEmptyState(70)))   // 65세 이상 · 점수 미도착
        assertTrue(showsCohortEmptyCard(scoreEmptyState(null))) // 나이 미상(조회 실패)
    }
}

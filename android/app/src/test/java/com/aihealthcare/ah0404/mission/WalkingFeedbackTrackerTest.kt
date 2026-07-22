package com.aihealthcare.ah0404.mission

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WalkingFeedbackTracker(#92) — "언제 신호를 낼지" 검증.
 * 진동·TTS 발화 자체(SharedWalkingFeedback + 공용 엔진)는 실기기 QA 소관, 여기선 신호 시점/중복만.
 */
class WalkingFeedbackTrackerTest {

    @Test
    fun started_emits_once_when_confirmed() {
        val t = WalkingFeedbackTracker()
        // 워밍업(미확정): 신호 없음.
        assertEquals(emptyList<WalkingFeedbackCue>(), t.onUpdate(confirmed = false, steps = 0, goal = 100))
        // 확정된 순간: STARTED 1회.
        assertEquals(listOf(WalkingFeedbackCue.STARTED), t.onUpdate(confirmed = true, steps = 3, goal = 100))
        // 이후 계속 확정 상태여도 재발생 없음.
        assertEquals(emptyList<WalkingFeedbackCue>(), t.onUpdate(confirmed = true, steps = 4, goal = 100))
    }

    @Test
    fun goal_reached_emits_once_at_or_after_target() {
        val t = WalkingFeedbackTracker()
        t.onUpdate(confirmed = true, steps = 10, goal = 100) // STARTED 소비
        assertEquals(emptyList<WalkingFeedbackCue>(), t.onUpdate(confirmed = true, steps = 99, goal = 100))
        assertEquals(listOf(WalkingFeedbackCue.GOAL_REACHED), t.onUpdate(confirmed = true, steps = 100, goal = 100))
        // 목표 초과해도 재발생 없음.
        assertEquals(emptyList<WalkingFeedbackCue>(), t.onUpdate(confirmed = true, steps = 150, goal = 100))
    }

    @Test
    fun started_and_goal_can_emit_in_same_update() {
        // 확정과 동시에 목표를 넘긴 경우(예: 소급 카운트가 목표 이상) 두 신호가 함께 나온다.
        val t = WalkingFeedbackTracker()
        assertEquals(
            listOf(WalkingFeedbackCue.STARTED, WalkingFeedbackCue.GOAL_REACHED),
            t.onUpdate(confirmed = true, steps = 120, goal = 100),
        )
    }

    @Test
    fun no_goal_cue_when_goal_null_or_zero() {
        val tNull = WalkingFeedbackTracker()
        assertEquals(listOf(WalkingFeedbackCue.STARTED), tNull.onUpdate(true, 9999, goal = null))
        assertEquals(emptyList<WalkingFeedbackCue>(), tNull.onUpdate(true, 9999, goal = null))

        val tZero = WalkingFeedbackTracker()
        assertEquals(listOf(WalkingFeedbackCue.STARTED), tZero.onUpdate(true, 9999, goal = 0))
        assertEquals(emptyList<WalkingFeedbackCue>(), tZero.onUpdate(true, 9999, goal = 0))
    }

    @Test
    fun reset_reenables_signals_for_new_session() {
        val t = WalkingFeedbackTracker()
        t.onUpdate(confirmed = true, steps = 100, goal = 100) // STARTED + GOAL_REACHED 소비
        assertEquals(emptyList<WalkingFeedbackCue>(), t.onUpdate(confirmed = true, steps = 100, goal = 100))

        t.reset()

        assertEquals(
            listOf(WalkingFeedbackCue.STARTED, WalkingFeedbackCue.GOAL_REACHED),
            t.onUpdate(confirmed = true, steps = 100, goal = 100),
        )
    }
}

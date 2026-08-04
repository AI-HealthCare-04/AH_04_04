package com.aihealthcare.ah0404.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * walkingGoalReached(#234/#239) — 측정 중 실시간 목표 도달 판정(분 누적/걸음).
 * 걷기 목표는 '당일 누적 시간(분)'이 기본이라 (오늘 누적분 + 이번 경과분) ≥ 목표분 을 검증한다.
 */
class WalkingGoalReachedTest {

    @Test
    fun `분 단위 - 단일 세션에서 경과분이 목표에 닿으면 도달`() {
        // EASY 20분: prior=0. 20분(1200s) 도달 순간 true, 직전(1199s)엔 false.
        assertFalse(walkingGoalReached("minutes", 20, priorDailyMin = 0.0, elapsedSec = 1199, steps = 0))
        assertTrue(walkingGoalReached("minutes", 20, priorDailyMin = 0.0, elapsedSec = 1200, steps = 0))
    }

    @Test
    fun `분 단위 - 나눠 걷기는 이전 누적분을 더해 판정`() {
        // 이전 세션 합 14분 + 이번 경과 6분(360s) = 20분 → 도달. 5분(300s)까진 19<20 미도달.
        assertFalse(walkingGoalReached("minutes", 20, priorDailyMin = 14.0, elapsedSec = 300, steps = 0))
        assertTrue(walkingGoalReached("minutes", 20, priorDailyMin = 14.0, elapsedSec = 360, steps = 0))
    }

    @Test
    fun `분 단위 - 이미 오늘 목표를 채운 상태면 새 세션에서 재발화하지 않는다`() {
        // 서버 조건 prior < target: 이미 목표 이상(prior ≥ target)이면 이번 세션은 '첫 교차'가 아니라 미발화(회귀, 지영 #239).
        assertFalse("prior == target 이면 미발화", walkingGoalReached("minutes", 20, priorDailyMin = 20.0, elapsedSec = 600, steps = 0))
        assertFalse("prior > target 이면 미발화", walkingGoalReached("minutes", 20, priorDailyMin = 25.0, elapsedSec = 1, steps = 0))
        // 반면 목표 직전(prior < target)에서 이번 세션이 넘기면 발화한다.
        assertTrue("prior < target 에서 이번 세션이 교차하면 발화", walkingGoalReached("minutes", 20, priorDailyMin = 19.0, elapsedSec = 120, steps = 0))
    }

    @Test
    fun `걸음 단위 - 걸음 수로 판정`() {
        assertFalse(walkingGoalReached("steps", 100, priorDailyMin = 0.0, elapsedSec = 99999, steps = 99))
        assertTrue(walkingGoalReached("steps", 100, priorDailyMin = 0.0, elapsedSec = 0, steps = 100))
    }

    @Test
    fun `알 수 없는 단위나 목표 0이면 도달 신호 없음`() {
        assertFalse(walkingGoalReached("count", 1, priorDailyMin = 999.0, elapsedSec = 99999, steps = 99999))
        assertFalse(walkingGoalReached("minutes", 0, priorDailyMin = 999.0, elapsedSec = 99999, steps = 0))
    }
}

/**
 * WalkingFeedbackTracker(#92) — "언제 신호를 낼지" 검증.
 * 진동·TTS 발화 자체(SharedWalkingFeedback + 공용 엔진)는 실기기 QA 소관, 여기선 신호 시점/중복만.
 * 목표 판정(분 누적/걸음)은 호출부 몫이라, 트래커엔 goalReached 불린만 온다.
 */
class WalkingFeedbackTrackerTest {

    @Test
    fun started_emits_once_when_confirmed() {
        val t = WalkingFeedbackTracker()
        // 워밍업(미확정): 신호 없음.
        assertEquals(emptyList<WalkingFeedbackCue>(), t.onUpdate(confirmed = false, goalReached = false))
        // 확정된 순간: STARTED 1회.
        assertEquals(listOf(WalkingFeedbackCue.STARTED), t.onUpdate(confirmed = true, goalReached = false))
        // 이후 계속 확정 상태여도 재발생 없음.
        assertEquals(emptyList<WalkingFeedbackCue>(), t.onUpdate(confirmed = true, goalReached = false))
    }

    @Test
    fun goal_reached_emits_once_at_or_after_target() {
        val t = WalkingFeedbackTracker()
        t.onUpdate(confirmed = true, goalReached = false) // STARTED 소비
        assertEquals(emptyList<WalkingFeedbackCue>(), t.onUpdate(confirmed = true, goalReached = false))
        // 호출부가 '목표 도달'로 계산해 넘긴 순간: GOAL_REACHED 1회.
        assertEquals(listOf(WalkingFeedbackCue.GOAL_REACHED), t.onUpdate(confirmed = true, goalReached = true))
        // 목표 초과(계속 true)여도 재발생 없음.
        assertEquals(emptyList<WalkingFeedbackCue>(), t.onUpdate(confirmed = true, goalReached = true))
    }

    @Test
    fun started_and_goal_can_emit_in_same_update() {
        // 확정과 동시에 목표 도달(예: 이전 세션 누적이 이미 목표에 근접) 시 두 신호가 함께 나온다.
        val t = WalkingFeedbackTracker()
        assertEquals(
            listOf(WalkingFeedbackCue.STARTED, WalkingFeedbackCue.GOAL_REACHED),
            t.onUpdate(confirmed = true, goalReached = true),
        )
    }

    @Test
    fun no_goal_cue_while_not_reached() {
        val t = WalkingFeedbackTracker()
        assertEquals(listOf(WalkingFeedbackCue.STARTED), t.onUpdate(true, goalReached = false))
        assertEquals(emptyList<WalkingFeedbackCue>(), t.onUpdate(true, goalReached = false))
    }

    @Test
    fun reset_reenables_signals_for_new_session() {
        val t = WalkingFeedbackTracker()
        t.onUpdate(confirmed = true, goalReached = true) // STARTED + GOAL_REACHED 소비
        assertEquals(emptyList<WalkingFeedbackCue>(), t.onUpdate(confirmed = true, goalReached = true))

        t.reset()

        assertEquals(
            listOf(WalkingFeedbackCue.STARTED, WalkingFeedbackCue.GOAL_REACHED),
            t.onUpdate(confirmed = true, goalReached = true),
        )
    }
}

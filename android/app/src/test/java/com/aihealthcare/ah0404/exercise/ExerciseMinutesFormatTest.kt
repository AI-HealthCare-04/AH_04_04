package com.aihealthcare.ah0404.exercise

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 오늘 누적 운동시간 표시 포맷(#235, 리뷰 #280) 경계 테스트.
 *
 *  핵심: 목표 미달(goalReached=false)인 9.5~9.9분이 반올림으로 "10분"으로 보이면 "10분인데 조금만 더"라는
 *  모순이 생긴다 → 미달 구간은 버림해 절대 목표치처럼 보이지 않게 한다. 달성 구간은 반올림으로 자연스럽게.
 */
class ExerciseMinutesFormatTest {

    @Test
    fun `목표 미달은 버림해 9점대가 10으로 보이지 않는다`() {
        // 리뷰가 짚은 경계값들 — 모두 미달이므로 9.x 그대로여야 한다(10 금지).
        assertEquals("9.5", formatExerciseMinutes(9.5f, goalReached = false))
        assertEquals("9.9", formatExerciseMinutes(9.9f, goalReached = false))
        // 반올림이면 10.0 이 됐을 값(9.96)도 미달 경로에선 9.9 로 버림.
        assertEquals("9.9", formatExerciseMinutes(9.96f, goalReached = false))
    }

    @Test
    fun `목표 달성은 반올림해 자연스럽게 보여준다`() {
        assertEquals("10", formatExerciseMinutes(10.0f, goalReached = true))
        assertEquals("10.4", formatExerciseMinutes(10.4f, goalReached = true))
        assertEquals("12", formatExerciseMinutes(12.0f, goalReached = true))
    }

    @Test
    fun `정수 분은 소수점 없이, 소수 분은 1자리로 표시한다`() {
        assertEquals("3", formatExerciseMinutes(3.0f, goalReached = false))
        assertEquals("7.5", formatExerciseMinutes(7.5f, goalReached = false))
        // 부동소수 오차로 9.9f 가 9.8 로 내려가지 않아야 한다(1e-3 보정).
        assertEquals("6.9", formatExerciseMinutes(6.9f, goalReached = false))
    }
}

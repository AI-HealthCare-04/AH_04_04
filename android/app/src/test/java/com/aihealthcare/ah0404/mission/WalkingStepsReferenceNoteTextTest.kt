package com.aihealthcare.ah0404.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 목표 단위별 '걸음 수 안내' 문구 선택(#232 리뷰 반영) 검증.
 *
 * 시간(minutes) 목표에서만 "성공은 걸은 시간" 문구가 참이고, 걸음(steps)·알 수 없는 단위에는
 * 노출하지 않아야(null) 화면 목표("N 걸음")와의 모순을 막는다. walkingGoalReached 의 단위 분기
 * ("minutes"/"steps"/else)와 같은 계약을 공유한다.
 */
class WalkingStepsReferenceNoteTextTest {

    @Test
    fun `시간(minutes) 목표에서는 참고용 문구를 노출한다`() {
        assertEquals(
            "걸음 수는 참고용이에요 — 미션 성공은 걸은 시간으로 정해져요.",
            walkingStepsReferenceNoteText("minutes"),
        )
    }

    @Test
    fun `걸음(steps) 목표에서는 노출하지 않는다`() {
        // 걸음이 곧 성공 지표라 "성공은 시간" 문구는 "목표 N 걸음"과 모순 — 띄우지 않는다.
        assertNull(walkingStepsReferenceNoteText("steps"))
    }

    @Test
    fun `알 수 없는 단위에서는 노출하지 않는다`() {
        // walkingGoalReached 의 else 분기와 정합 — 안전하게 아무 것도 단정하지 않는다.
        assertNull(walkingStepsReferenceNoteText("count"))
        assertNull(walkingStepsReferenceNoteText(""))
    }
}

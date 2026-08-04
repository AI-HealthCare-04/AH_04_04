package com.aihealthcare.ah0404.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 점수 재평가 상태 판정(#388).
 *
 * #396 이 하루 1회 제한을 넣으면서 `recalculated=false` 응답이 생겼는데, 앱은 그걸 보지 않고
 * "점수가 있으면 반영됨"으로 처리하고 있었다. 그러면 **오늘 이미 계산한 옛 점수를 받고도
 * "바로 반영됐어요"** 라고 말하게 된다. 이 판정이 그 어긋남을 막는다.
 */
class ScoreRefreshTest {

    @Test
    fun a_fresh_calculation_is_applied() {
        assertEquals(ScoreRefreshState.APPLIED, scoreRefreshResult(recalculated = true, muscleScore = 72))
    }

    @Test
    fun the_daily_limit_is_not_the_same_as_applied() {
        // 점수는 있지만 새로 계산된 것이 아니다 — 이걸 APPLIED 로 뭉뚱그리면 어제 점수를
        //   새 점수로 오해시킨다.
        assertEquals(
            ScoreRefreshState.ALREADY_TODAY,
            scoreRefreshResult(recalculated = false, muscleScore = 72),
        )
    }

    @Test
    fun no_score_is_not_eligible_regardless_of_recalculation() {
        // 점수 자체가 없으면(연령 미지원 등) 다시 계산했든 아니든 제공 대상이 아니다.
        assertEquals(ScoreRefreshState.NOT_ELIGIBLE, scoreRefreshResult(recalculated = true, muscleScore = null))
        assertEquals(ScoreRefreshState.NOT_ELIGIBLE, scoreRefreshResult(recalculated = false, muscleScore = null))
    }

    // ── 버튼 활성 조건(리뷰 P2) ────────────────────────────────────────────
    // 다시 눌러도 서버가 같은 답을 주는 상태에서 버튼을 살려두면 "눌러도 안 바뀐다"만 반복시킨다.

    @Test
    fun the_first_press_and_a_retry_after_failure_are_allowed() {
        assertTrue("아직 안 눌렀다", canRequestScoreRefresh(null))
        assertTrue("네트워크·서버 실패는 다시 눌러 볼 값이 있다", canRequestScoreRefresh(ScoreRefreshState.FAILED))
    }

    @Test
    fun states_that_return_the_same_answer_disable_the_button() {
        listOf(
            ScoreRefreshState.IN_PROGRESS,
            // APPLIED 직후 다시 부르면 서버가 recalculated=false 를 준다 — 같은 답이다.
            ScoreRefreshState.APPLIED,
            ScoreRefreshState.ALREADY_TODAY,
            ScoreRefreshState.NOT_ELIGIBLE,
        ).forEach { assertFalse("state=$it 에서는 눌릴 수 없어야 한다", canRequestScoreRefresh(it)) }
    }

    // ── 문구 ────────────────────────────────────────────────────────────────

    @Test
    fun nothing_is_said_before_the_button_is_pressed() {
        assertNull(scoreRefreshStatusText(null))
    }

    @Test
    fun the_daily_limit_explains_why_the_number_did_not_change() {
        // 눌렀는데 숫자가 그대로면 고장으로 읽힌다. 이유와 다음 시점을 함께 줘야 한다.
        val text = scoreRefreshStatusText(ScoreRefreshState.ALREADY_TODAY).orEmpty()
        assertTrue("이미 계산했다는 사실: $text", text.contains("오늘은 이미"))
        assertTrue("하루 한 번이라는 규칙: $text", text.contains("하루에 한 번"))
        assertTrue("언제 다시 되는지: $text", text.contains("내일"))
    }

    @Test
    fun every_state_that_shows_a_message_says_something() {
        ScoreRefreshState.entries.forEach { state ->
            val text = scoreRefreshStatusText(state)
            assertTrue("state=$state 에 문구가 없다", !text.isNullOrBlank())
        }
    }

    @Test
    fun no_message_promises_automatic_application() {
        // 자동 재평가 배치가 없다. "내일 반영돼요" 같은 말은 사용자가 다시 누르지 않으면
        //   영영 반영되지 않는 약속이 된다(리뷰 P1).
        ScoreRefreshState.entries.forEach { state ->
            val text = scoreRefreshStatusText(state).orEmpty()
            assertFalse("자동 반영을 약속하면 안 된다(state=$state): $text", text.contains("내일 반영"))
        }
    }

    @Test
    fun the_applied_message_points_at_the_score_above() {
        // 버튼이 점수 카드 아래에 있으므로 "위 점수" 로 어디를 보라고 말해준다.
        assertTrue(scoreRefreshStatusText(ScoreRefreshState.APPLIED).orEmpty().contains("위 점수"))
    }
}

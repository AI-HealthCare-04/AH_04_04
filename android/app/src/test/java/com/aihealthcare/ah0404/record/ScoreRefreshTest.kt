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

    @Test
    fun only_a_failure_offers_a_retry() {
        // 422·하루 1회·정상 반영은 다시 눌러도 결과가 같다 — 재시도 버튼은 헛된 기대만 준다.
        assertTrue(canRetryScoreRefresh(ScoreRefreshState.FAILED))
        listOf(
            null,
            ScoreRefreshState.IN_PROGRESS,
            ScoreRefreshState.APPLIED,
            ScoreRefreshState.ALREADY_TODAY,
            ScoreRefreshState.NOT_ELIGIBLE,
        ).forEach { assertFalse("state=$it", canRetryScoreRefresh(it)) }
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
    fun the_applied_message_points_at_the_score_above() {
        // 버튼이 점수 카드 아래에 있으므로 "위 점수" 로 어디를 보라고 말해준다.
        assertTrue(scoreRefreshStatusText(ScoreRefreshState.APPLIED).orEmpty().contains("위 점수"))
    }
}

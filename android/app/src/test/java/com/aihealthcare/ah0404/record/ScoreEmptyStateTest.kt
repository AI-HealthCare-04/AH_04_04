package com.aihealthcare.ah0404.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 점수가 없을 때의 연령별 안내(1차 검토 피드백).
 *
 * 50-64세는 새 모델과 함께 **준비 중**이지만, 50세 미만은 학습 데이터(고령층 건강 조사)가 없어
 * **제공 계획 자체가 없다.** 두 경우에 같은 '준비 중' 문구를 쓰면 50세 미만 사용자가 기다리면 되는
 * 것으로 오해한다.
 *
 * 실제 사고는 두 섹션이 **각자 분기**하면서 났다 — 대시보드는 연령을 봤지만 '근육 건강 정보' 섹션은
 * 점수 유무만 봐서, #385 로 두 섹션이 한 탭에 모인 뒤 50세 미만 사용자가 한 화면에서
 * "제공하지 않는다"와 "점수가 준비되면 보여드려요"를 동시에 보게 됐다.
 */
class ScoreEmptyStateTest {

    @Test
    fun under_fifty_is_never_treated_as_preparing() {
        assertEquals(ScoreEmptyState.UNDER_AGE, scoreEmptyState(20))
        assertEquals(ScoreEmptyState.UNDER_AGE, scoreEmptyState(49))
    }

    @Test
    fun fifty_to_sixtyfour_is_preparing() {
        // 경계 확인: 50 은 준비 중 쪽이다(제공 계획이 있는 구간).
        assertEquals(ScoreEmptyState.PREPARING, scoreEmptyState(MIN_SCORE_AGE))
        assertEquals(ScoreEmptyState.PREPARING, scoreEmptyState(64))
    }

    @Test
    fun sixtyfive_and_over_without_a_score_is_pending() {
        // 제공 대상인데 점수가 아직 안 온 경우 — 잠시 후 재확인이 맞다.
        assertEquals(ScoreEmptyState.PENDING, scoreEmptyState(65))
        assertEquals(ScoreEmptyState.PENDING, scoreEmptyState(80))
    }

    @Test
    fun unknown_age_is_pending_not_under_age() {
        // 나이 미상은 조회 실패다. 여기서 '제공하지 않는다'고 단정하면 65세 이상에게 틀린 안내가 된다.
        assertEquals(ScoreEmptyState.PENDING, scoreEmptyState(null))
    }

    @Test
    fun the_whole_age_range_maps_to_exactly_three_regions() {
        // 경계만 찍으면 구간 한가운데의 실수를 놓친다. 전 구간을 훑어 경계가 정확히 세 덩어리인지 본다.
        (0..49).forEach { assertEquals("age=$it", ScoreEmptyState.UNDER_AGE, scoreEmptyState(it)) }
        (50..64).forEach { assertEquals("age=$it", ScoreEmptyState.PREPARING, scoreEmptyState(it)) }
        (65..120).forEach { assertEquals("age=$it", ScoreEmptyState.PENDING, scoreEmptyState(it)) }
    }

    // ── 문구 회귀 방지 ────────────────────────────────────────────────────────
    // 50세 미만 안내에 '준비/아직/곧' 같은 말이 다시 들어오면 기다리면 열리는 것으로 읽힌다.

    @Test
    fun under_age_copy_never_promises_a_future_score() {
        listOf(
            UNDER_AGE_SCORE_TITLE,
            UNDER_AGE_SCORE_BODY,
        ).forEach { copy ->
            listOf("준비", "아직", "곧 ", "예정").forEach { banned ->
                assertFalse("50세 미만 안내에 '$banned' 가 들어가면 안 된다: $copy", copy.contains(banned))
            }
        }
    }

    @Test
    fun under_age_copy_explains_why_and_offers_what_is_usable() {
        // 왜 안 되는지(자료 없음)와 대신 무엇을 할 수 있는지가 함께 있어야 안내로 기능한다.
        assertEquals(true, UNDER_AGE_SCORE_BODY.contains("자료가 없어"))
        assertEquals(true, UNDER_AGE_SCORE_BODY.contains("챌린지"))
    }
}

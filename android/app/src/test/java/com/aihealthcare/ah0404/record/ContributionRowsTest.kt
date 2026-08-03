package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.ContributionItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 점수 기여도 표시행(#406) — 화이트리스트 필터 + |영향| 큰 순 정렬 + 부호→색 방향.
 * 백엔드가 근력·걷기·허리만 주지만, 화면에서도 화이트리스트로 한 번 더 거른다(체중·나이 유입 방지).
 */
class ContributionRowsTest {

    private fun item(feature: String, effect: Double) = ContributionItemDto(feature, effect)

    @Test
    fun sorts_by_magnitude_and_maps_labels() {
        // 이슈 예시(사용자 A): 허리 -1.29 / 근력 -0.17 / 걷기 -0.13 → |영향| 큰 순 = 허리, 근력, 걷기.
        val rows = contributionRows(
            listOf(item("walk_days", -0.13), item("waist_cm", -1.29), item("musc_days", -0.17)),
        )
        assertEquals(listOf("허리둘레", "근력 운동", "걷기"), rows.map { it.label })
        assertTrue("모두 음수 → 개선 여지(올리는 중 아님)", rows.all { !it.raising })
    }

    @Test
    fun positive_effect_is_raising() {
        val rows = contributionRows(listOf(item("musc_days", 0.42), item("waist_cm", -0.30)))
        val byLabel = rows.associateBy { it.label }
        assertTrue("양수 = 점수를 올리는 중", byLabel.getValue("근력 운동").raising)
        assertFalse("음수 = 개선 여지", byLabel.getValue("허리둘레").raising)
    }

    @Test
    fun drops_features_not_in_whitelist() {
        // 백엔드가 실수로 체중·나이·성별을 줘도 화면엔 절대 나오면 안 된다(오해 방지 정책).
        val rows = contributionRows(
            listOf(item("weight_kg", -2.0), item("age", 1.0), item("sex", 0.5), item("walk_days", -0.1)),
        )
        assertEquals(listOf("걷기"), rows.map { it.label })
    }

    @Test
    fun empty_input_yields_empty() {
        assertTrue(contributionRows(emptyList()).isEmpty())
    }
}

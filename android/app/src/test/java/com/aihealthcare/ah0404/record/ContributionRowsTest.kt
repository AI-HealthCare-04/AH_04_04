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

    @Test
    fun zero_effect_is_dropped_not_shown_as_improvable() {
        // 0(영향 없음)은 '여기서 더 올릴 수 있어요'로 오표시하지 않고 행에서 제외한다(#406 리뷰). 양수·음수·0 혼합.
        val rows = contributionRows(
            listOf(item("musc_days", 0.0), item("waist_cm", -0.5), item("walk_days", 0.3)),
        )
        assertEquals(listOf("허리둘레", "걷기"), rows.map { it.label }) // 근력(0.0)은 빠진다
        assertTrue("0 항목은 개선 여지로도 나오지 않는다", rows.none { it.label == "근력 운동" })
    }

    @Test
    fun all_zero_yields_empty_so_card_hides() {
        // 전부 0이면 빈 목록 → ContributionCard 가 통째로 숨는다(표시 여부가 이 순수 함수로 고정된다).
        val rows = contributionRows(
            listOf(item("musc_days", 0.0), item("walk_days", 0.0), item("waist_cm", 0.0)),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun tiny_rounding_noise_is_treated_as_zero() {
        // 백엔드 round(,4) 노이즈(|x| < 5e-5)는 0으로 취급해 제외한다 — API 반올림 단위에 맞춘 epsilon.
        val rows = contributionRows(listOf(item("walk_days", 0.00004), item("waist_cm", -0.4)))
        assertEquals(listOf("허리둘레"), rows.map { it.label })
    }

    @Test
    fun bar_percent_label_reads_fraction_as_percent() {
        // TalkBack 음성 안내용 백분율 문구(막대 길이=영향 크기). 수치(log-odds)는 노출하지 않는다.
        assertEquals("100%", barPercentLabel(1f))
        assertEquals("50%", barPercentLabel(0.5f))
        assertEquals("6%", barPercentLabel(0.06f))
    }
}

package com.aihealthcare.ah0404.record

import com.aihealthcare.ah0404.network.RiskHistoryItem
import com.aihealthcare.ah0404.network.RiskHistoryResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskTrendTest {

    private fun item(
        date: String,
        score: Double?,
        status: String,
        change: Double? = null,
    ) = RiskHistoryItem(
        createdAt = date,
        careStage = "maintain",
        riskScore = score,
        changePercentagePoints = change,
        comparisonStatus = status,
    )

    @Test
    fun model_changed_starts_a_new_visual_segment() {
        val segments = buildRiskTrendSegments(
            listOf(
                item("2026-07-01", 0.32, "baseline"),
                item("2026-07-08", 0.30, "comparable", -2.0),
                item("2026-07-15", 0.24, "model_changed"),
                item("2026-07-22", 0.21, "comparable", -3.0),
            ),
        )

        assertEquals(listOf(2, 2), segments.map { it.size })
        assertEquals("2026-07-15", segments[1].first().createdAt)
    }

    @Test
    fun missing_or_invalid_scores_are_not_drawn_as_zero() {
        val segments = buildRiskTrendSegments(
            listOf(
                item("2026-07-01", null, "baseline"),
                item("2026-07-08", -0.1, "comparable"),
                item("2026-07-15", 0.18, "comparable", -1.0),
            ),
        )

        assertEquals(1, segments.single().size)
        assertEquals(0.18, segments.single().single().score, 0.0)
    }

    @Test
    fun change_copy_is_non_diagnostic_and_handles_model_boundary() {
        val modelChanged = buildRiskTrendSegments(
            listOf(item("2026-07-15", 0.24, "model_changed")),
        ).single().single()
        val improved = buildRiskTrendSegments(
            listOf(item("2026-07-22", 0.18, "comparable", -6.0)),
        ).single().single()
        val increased = buildRiskTrendSegments(
            listOf(item("2026-07-22", 0.30, "comparable", 6.0)),
        ).single().single()
        val tinyChange = buildRiskTrendSegments(
            listOf(item("2026-07-22", 0.18, "comparable", -0.9)),
        ).single().single()

        assertEquals("새로운 기준으로 다시 살펴보기 시작했어요.", changeDescription(modelChanged))
        assertTrue(changeDescription(improved).contains("낮아졌어요"))
        assertEquals(
            "지난 기록보다 6.0%p 높아졌어요. 생활습관을 조금 더 살펴봐요.",
            changeDescription(increased),
        )
        assertEquals("지난 기록과 비슷하게 유지되고 있어요.", changeDescription(tinyChange))
    }

    @Test
    fun accessibility_description_announces_model_boundary() {
        val segments = buildRiskTrendSegments(
            listOf(
                item("2026-07-01", 0.32, "baseline"),
                item("2026-07-08", 0.30, "comparable", -2.0),
                item("2026-07-15", 0.24, "model_changed"),
            ),
        )

        assertEquals(
            "2026.07.01 관리 필요도 32%, 2026.07.08 관리 필요도 30%. " +
                "새로운 기준으로 다시 시작. 2026.07.15 관리 필요도 24%",
            riskTrendContentDescription(segments),
        )
    }

    @Test
    fun x_axis_uses_elapsed_date_ratio_and_falls_back_for_invalid_dates() {
        val dated = buildRiskTrendSegments(
            listOf(
                item("2026-07-01", 0.32, "baseline"),
                item("2026-07-02", 0.30, "comparable", -2.0),
                item("2026-07-20", 0.24, "comparable", -6.0),
            ),
        ).flatten()
        val fractions = buildRiskTrendXFractions(dated)

        assertEquals(0f, fractions[0], 0f)
        assertEquals(1f / 19f, fractions[1], 0.0001f)
        assertEquals(1f, fractions[2], 0f)

        val invalid = dated.mapIndexed { index, point ->
            if (index == 1) point.copy(createdAt = "날짜 없음") else point
        }
        assertEquals(listOf(0f, 0.5f, 1f), buildRiskTrendXFractions(invalid))

        val sameDay = dated.map { it.copy(createdAt = "2026-07-22T09:00:00+09:00") }
        assertEquals(listOf(0f, 0.5f, 1f), buildRiskTrendXFractions(sameDay))
    }

    @Test
    fun history_contract_decodes_continuous_fields() {
        val response = Json.decodeFromString<RiskHistoryResponse>(
            """
            {
              "predictions": [
                {
                  "prediction_id": 7,
                  "created_at": "2026-07-21T09:00:00+09:00",
                  "risk_score": 0.281,
                  "change_percentage_points": -4.3,
                  "comparison_status": "comparable",
                  "care_stage": "maintain"
                }
              ]
            }
            """.trimIndent(),
        )

        val item = response.predictions.single()
        assertEquals(0.281, item.riskScore ?: error("risk_score missing"), 0.0)
        assertEquals(-4.3, item.changePercentagePoints ?: error("change missing"), 0.0)
        assertEquals("comparable", item.comparisonStatus)
    }
}

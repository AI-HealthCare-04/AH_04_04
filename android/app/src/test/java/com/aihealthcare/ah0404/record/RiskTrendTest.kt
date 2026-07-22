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

        assertEquals("새로운 기준으로 다시 살펴보기 시작했어요.", changeDescription(modelChanged))
        assertTrue(changeDescription(improved).contains("낮아졌어요"))
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

package com.aihealthcare.ah0404.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `_13 나의 기록` 화면에서 사용하는 예측 이력과 미션 기록 DTO. */

@Serializable
data class RiskHistoryItem(
    @SerialName("created_at") val createdAt: String,
    @SerialName("care_stage") val careStage: String, // Android 전환 기간의 임시 호환 필드
    @SerialName("prediction_id") val predictionId: Int = 0,
    @SerialName("risk_score") val riskScore: Double? = null,
    @SerialName("change_percentage_points") val changePercentagePoints: Double? = null,
    @SerialName("comparison_status") val comparisonStatus: String = "baseline",
)

@Serializable
data class RiskHistoryResponse(
    /** 서버 계약에 따라 오래된 기록부터 최신 기록 순서로 온다. */
    val predictions: List<RiskHistoryItem> = emptyList(),
)

@Serializable
data class MissionLogItem(
    @SerialName("mission_log_id") val missionLogId: Int,
    @SerialName("mission_type") val missionType: String,
    val success: Boolean,
    @SerialName("counted_for_daily") val countedForDaily: Boolean,
    @SerialName("earned_points") val earnedPoints: Int,
)

@Serializable
data class MissionLogListResponse(
    val logs: List<MissionLogItem> = emptyList(),
)

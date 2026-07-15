package com.aihealthcare.ah0404.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `_13 나의 기록` 응답 DTO — dev 백엔드 정본(#62 위험도 이력, mission-logs 목록) 기준.
 *
 *  ⚠️ 비노출 계약(#57): 위험도 이력은 care_stage 만 온다. risk_level·risk_score 는 서버가
 *     응답에서 제외하며, 앱도 절대 표시/요청하지 않는다.
 *  created_at 은 KST(+09:00) ISO8601 문자열. 화면에서는 날짜(YYYY.MM.DD)만 사용한다.
 */

// ── GET /risk-predictions/me/history ────────────────────────────────────────
@Serializable
data class RiskHistoryItem(
    @SerialName("created_at") val createdAt: String,
    @SerialName("care_stage") val careStage: String, // good | maintain | action_needed
)

@Serializable
data class RiskHistoryResponse(
    val predictions: List<RiskHistoryItem> = emptyList(),
)

// ── GET /mission-logs ───────────────────────────────────────────────────────
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

package com.aihealthcare.ah0404.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `_13 나의 기록` 화면에서 사용하는 예측 이력과 미션 기록 DTO. */

/** 예측 대시보드(#193) 개인화 입력 응답. 프로필 미완이면 신체값 null → 앱이 기본값 폴백. */
@Serializable
data class PredictionInputsResponse(
    val sex: String? = null, // "male" | "female" | null
    @SerialName("birth_date") val birthDate: String? = null, // "YYYY-MM-DD" | null
    @SerialName("height_cm") val heightCm: Double? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("waist_cm") val waistCm: Double? = null,
    @SerialName("walk_days") val walkDays: Int = 0,
    @SerialName("musc_days") val muscDays: Int = 0,
)

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
    // 기록 탭 달력·선그래프(#기록탭 §5.1/§5.2): 미션명 + 완료 시각(KST ISO). 구버전 응답 대비 기본값.
    val title: String = "",
    @SerialName("completed_at") val completedAt: String? = null,
    val success: Boolean,
    @SerialName("counted_for_daily") val countedForDaily: Boolean,
    @SerialName("earned_points") val earnedPoints: Int,
)

@Serializable
data class MissionLogListResponse(
    val logs: List<MissionLogItem> = emptyList(),
)

/** 걷기 일별 막대(#기록탭 §5.3). 걷기 없는 날도 0으로 내려온다. */
@Serializable
data class WalkingDayPoint(
    val date: String, // "YYYY-MM-DD"
    val steps: Int = 0,
    val minutes: Double = 0.0,
)

@Serializable
data class WalkingDailyResponse(
    val days: List<WalkingDayPoint> = emptyList(),
)

/** 챌린지 유형별 누적 완료(#기록탭 §5.4). 0회 유형도 포함(범례 회색). */
@Serializable
data class ChallengeTypeTotal(
    @SerialName("mission_type") val missionType: String, // walking | exercise | meal | game
    val count: Int = 0,
)

@Serializable
data class ChallengeTotalsResponse(
    val total: Int = 0,
    @SerialName("by_type") val byType: List<ChallengeTypeTotal> = emptyList(),
)

/** 월별 스탬프(#기록탭 §5.2). 활동 있는 날만 담기고 나머지는 앱이 none 처리. */
@Serializable
data class StampDay(
    val date: String, // "YYYY-MM-DD"
    @SerialName("daily_result") val dailyResult: String, // none | success | great_success
    @SerialName("counted_mission_count") val countedMissionCount: Int = 0,
    @SerialName("earned_points") val earnedPoints: Int = 0,
)

@Serializable
data class StampsResponse(
    val month: String,
    val days: List<StampDay> = emptyList(),
)

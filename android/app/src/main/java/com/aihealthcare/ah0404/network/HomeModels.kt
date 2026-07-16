package com.aihealthcare.ah0404.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 홈(_3) 통합 응답 DTO — dev 백엔드 정본(GET /home) 기준. (기존 느슨한 JsonElement 매핑 → 타입 확정)
 *
 *  ⚠️ 비노출 계약(#57): latest_prediction 은 care_stage + display_message 만(위험도 점수/등급 없음).
 *  today_walking 은 #69 로 추가된 오늘 걷기 실적(분·걸음). 걷기 목표(분)는 여기 없고 GET /missions 가 원천.
 */
@Serializable
data class HomeResponse(
    val user: HomeUser,
    @SerialName("point_balance") val pointBalance: HomePointBalance,
    @SerialName("activity_profile") val activityProfile: HomeActivityProfile,
    @SerialName("latest_prediction") val latestPrediction: HomeLatestPrediction? = null,
    @SerialName("today_summary") val todaySummary: HomeTodaySummary,
    @SerialName("available_mission_summary") val availableMissionSummary: HomeAvailableMissionSummary,
    @SerialName("today_walking") val todayWalking: HomeTodayWalking = HomeTodayWalking(),
)

@Serializable
data class HomeUser(val nickname: String = "")

@Serializable
data class HomePointBalance(@SerialName("current_points") val currentPoints: Int = 0)

@Serializable
data class HomeActivityProfile(@SerialName("current_level") val currentLevel: String = "easy")

@Serializable
data class HomeLatestPrediction(
    @SerialName("care_stage") val careStage: String, // good | maintain | action_needed
    @SerialName("display_message") val displayMessage: String,
)

@Serializable
data class HomeTodaySummary(@SerialName("counted_mission_count") val countedMissionCount: Int = 0)

@Serializable
data class HomeAvailableMissionSummary(
    val meal: Int = 0,
    val exercise: Int = 0,
    val walking: Int = 0,
    val game: Int = 0,
)

@Serializable
data class HomeTodayWalking(
    @SerialName("daily_total_min") val dailyTotalMin: Double = 0.0,
    @SerialName("daily_total_steps") val dailyTotalSteps: Int = 0,
)

package com.example.myapplication.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    @SerialName("authorization_code") val authorizationCode: String
)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String
)

@Serializable
data class Mission(
    @SerialName("mission_template_id") val missionTemplateId: Int,
    @SerialName("mission_type") val missionType: String,
    val title: String,
    val description: String,
    val level: String,
    @SerialName("target_value") val targetValue: Int,
    @SerialName("target_unit") val targetUnit: String,
    @SerialName("requires_safety_notice") val requiresSafetyNotice: Boolean,
    @SerialName("daily_count_limit") val dailyCountLimit: Int? = null,
    @SerialName("reward_points") val rewardPoints: Int
)

@Serializable
data class MissionsResponse(
    val missions: List<Mission>
)

package com.aihealthcare.ah0404.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `_14 내 정보` DTO — dev 백엔드 정본(#67 `/users/me` 통합) 기준.
 *
 *  birth_date/sex 는 건강체크 전이면 null. current_points 는 미션 적립 합, activity_level 은
 *  프로필 없으면 "easy"(홈 표시 기본값과 동일). created_at 은 KST(+09:00) ISO8601 문자열.
 */
@Serializable
data class UserInfoResponse(
    @SerialName("user_id") val userId: Int,
    val provider: String,
    val nickname: String,
    @SerialName("onboarding_status") val onboardingStatus: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("birth_date") val birthDate: String? = null, // "YYYY-MM-DD" | null
    val sex: String? = null,                                 // "male" | "female" | null
    @SerialName("current_points") val currentPoints: Int = 0,
    @SerialName("activity_level") val activityLevel: String = "easy", // easy | normal | hard
)

// PATCH /users/me 요청 — 닉네임 변경(1~50자). 항상 nickname 을 실어 보낸다.
@Serializable
data class UserUpdateRequest(
    val nickname: String,
)

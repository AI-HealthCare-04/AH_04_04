package com.aihealthcare.ah0404.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 설정 '내 정보' 신체 정보(#기록탭 §2). GET /health-profiles/me/latest 응답 중 필요한 필드만(ignoreUnknownKeys). */
@Serializable
data class HealthProfileLatest(
    val sex: String? = null,       // "male" | "female"
    val age: Int = 0,
    @SerialName("height_cm") val heightCm: Double = 0.0,
    @SerialName("weight_kg") val weightKg: Double = 0.0,
    @SerialName("waist_cm") val waistCm: Double? = null,
    @SerialName("kidney_status") val kidneyStatus: String = "unknown",
)

/**
 * 신체 정보 편집(#기록탭 §2, PATCH /health-profiles/me). 폼의 현재 값을 전부 보낸다.
 *   허리둘레는 명시적 null 로 '측정 안 함'을 지울 수 있다(서버가 model_fields_set 로 구분).
 *   서버는 최신 스냅샷에 덮어 새 행을 만들고, 유효 변경이 없으면 400 을 준다.
 */
@Serializable
data class HealthProfilePatchRequest(
    @SerialName("height_cm") val heightCm: Double,
    @SerialName("weight_kg") val weightKg: Double,
    @SerialName("waist_cm") val waistCm: Double?,
    @SerialName("kidney_status") val kidneyStatus: String,
)

package com.aihealthcare.ah0404.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 설정(_15) DTO — dev 백엔드 정본(GET/PATCH /users/me/settings) 기준.
 *
 *  font_size/sound_size 는 small|medium|large. pet_type 은 자유 문자열(서버 기본 "default").
 *  ⚠️ NetworkClient Json 은 encodeDefaults=false → PATCH 는 null 필드가 전송에서 빠져
 *     "보낸 필드만 변경"(부분 업데이트)이 그대로 성립한다.
 */
@Serializable
data class UserSettingsResponse(
    @SerialName("font_size") val fontSize: String = "medium",
    @SerialName("sound_size") val soundSize: String = "medium",
    @SerialName("pet_type") val petType: String = "default",
    @SerialName("music_enabled") val musicEnabled: Boolean = true,
)

@Serializable
data class UserSettingsUpdateRequest(
    @SerialName("font_size") val fontSize: String? = null,
    @SerialName("sound_size") val soundSize: String? = null,
    @SerialName("pet_type") val petType: String? = null,
    @SerialName("music_enabled") val musicEnabled: Boolean? = null,
)

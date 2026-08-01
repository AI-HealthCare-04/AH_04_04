package com.aihealthcare.ah0404.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 클라이언트 텔레메트리 DTO — dev 백엔드 정본(POST /events/sts-overlay-shown) 기준(#366).
 *
 *  tier 는 basic|strong(필수), score_band 는 good|maintain|caution|null — 서버가 Literal 로
 *  검증하고 벗어나면 422. sts_sec/bmi 도 저장 스키마 경계(0-999.99 / 0-999.9)를 서버가 검증한다.
 *  fire-and-forget 전송이라 422 여도 화면에는 영향이 없다.
 *  ⚠️ NetworkClient Json 은 encodeDefaults=false → null 필드는 전송에서 빠진다(서버 기본 null).
 */
@Serializable
data class StsOverlayShownRequest(
    val tier: String,
    @SerialName("sts_sec") val stsSec: Double? = null,
    val bmi: Double? = null,
    @SerialName("score_band") val scoreBand: String? = null,
)

@Serializable
data class StsOverlayShownResponse(
    val recorded: Boolean = false,
)

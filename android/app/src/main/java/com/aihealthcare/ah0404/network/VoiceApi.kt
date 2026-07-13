package com.aihealthcare.ah0404.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * ============================================================================
 *  VoiceApi : STT 음성 보조입력 파서(#40) 호출 계약
 * ============================================================================
 *
 *  백엔드 계약(변경 금지):
 *    POST /api/v1/health-check/voice/parse   (stateless, 세션 무관)
 *    Request : { "field": "height_cm", "raw_transcript": "백육십" }
 *    Response: { "field": "height_cm", "value": 160.0, "needs_confirmation": true }
 *
 *  - 앱은 현재 질문의 field 를 알고 있으므로 field + 온디바이스 STT 원문만 전송.
 *  - 파싱 실패/모호/"몰라요" → value=null, needs_confirmation=false (200, 에러 아님)
 *    → 앱은 수동입력 폼으로 폴백.
 *  - value 타입: float / ISO 문자열 / enum·성별 문자열 / 예·아니오 bool → JsonElement 로 그대로 수신.
 * ============================================================================
 */

/** 음성으로 입력받는 건강 프로필 필드(백엔드 VoiceParseField 와 1:1). */
enum class VoiceField(val serverKey: String, val label: String) {
    HEIGHT_CM("height_cm", "키 (cm)"),
    WEIGHT_KG("weight_kg", "몸무게 (kg)"),
    WAIST_CM("waist_cm", "허리둘레 (cm)"),
    BIRTH_DATE("birth_date", "생년월일"),
    SEX("sex", "성별"),
    WALKING_PRACTICE("walking_practice", "걷기 실천"),
    STRENGTH_EXERCISE("strength_exercise", "근력 운동"),
    KIDNEY_STATUS("kidney_status", "신장 상태"),
    PROTEIN_RESTRICTION_STATUS("protein_restriction_status", "단백질 제한"),
}

@Serializable
data class VoiceParseRequest(
    val field: String,
    @SerialName("raw_transcript") val rawTranscript: String,
)

@Serializable
data class VoiceParseResponse(
    val field: String,
    // bool | float | str | null 을 모두 받을 수 있어야 하므로 JsonElement 로 수신.
    val value: JsonElement? = null,
    @SerialName("needs_confirmation") val needsConfirmation: Boolean,
)

interface VoiceApi {
    @POST("health-check/voice/parse")
    suspend fun parseVoice(@Body body: VoiceParseRequest): VoiceParseResponse
}

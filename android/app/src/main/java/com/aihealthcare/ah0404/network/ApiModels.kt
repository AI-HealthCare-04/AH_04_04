package com.aihealthcare.ah0404.network

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    // 백엔드 DTO가 nullable(str | None)이라 안전하게 nullable로 맞춤
    val description: String? = null,
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

// =====================================================================================
//  미션 수행 흐름 (걷기): POST /mission-logs → POST /sensor-sessions → PATCH /mission-logs/{id}
//  필드/이름은 백엔드 DTO(app/dtos/mission.py, sensor.py)와 1:1로 맞춘 계약이다.
//  Json 설정이 encodeDefaults=false 라 기본값(null)인 optional 필드는 전송에서 빠진다.
// =====================================================================================

// [요청] 미션 시작/생성. 걷기는 status="in_progress" 로 시작(운동도 동일). 식사/게임은 "completed".
@Serializable
data class MissionLogCreateRequest(
    @SerialName("mission_template_id") val missionTemplateId: Int,
    @SerialName("mission_type") val missionType: String,   // "walking" | "exercise" | "meal" | "game"
    val status: String,                                    // "in_progress" | "completed"
    // 운동(requires_safety_notice=true)에서만 필요. 걷기 데모에선 null로 두면 전송 안 됨.
    @SerialName("safety_notice_confirmed") val safetyNoticeConfirmed: Boolean? = null,
    // 기기에서 이 기록(측정)이 만들어진 시각(ISO-8601). 서버가 재전송을 같은 수행으로 알아보는 자연 키(#158).
    //   재전송 시 반드시 같은 값을 다시 보내야 중복 집계가 막힌다 → 측정 '시작' 시각을 한 번 잡아 고정한다.
    //   null 이면 서버 유니크에서 제외돼 종전 동작(중복 방지 없음)과 호환.
    @SerialName("created_on_device_at") val createdOnDeviceAt: String? = null,
)

@Serializable
data class MissionLogCreateResponse(
    @SerialName("mission_log_id") val missionLogId: Int,
    val status: String,
    val success: Boolean,
    @SerialName("counted_for_daily") val countedForDaily: Boolean,
    @SerialName("daily_limit_reached") val dailyLimitReached: Boolean = false,   // 식사 전용 필드. 걷기 응답엔 없을 수 있어 기본값
    @SerialName("earned_points") val earnedPoints: Int,
    @SerialName("daily_result") val dailyResult: String,                 // none | success | great_success
)

// [요청] 센서 측정 결과 저장. recognition_status 는 백엔드에서 필수(기본값 없음).
@Serializable
data class SensorSessionCreateRequest(
    @SerialName("mission_log_id") val missionLogId: Int,
    @SerialName("sensor_type") val sensorType: String,          // MVP: "accelerometer"
    @SerialName("recognition_status") val recognitionStatus: String,  // "success" | "low_confidence" | "failed" | "manual_override"
    @SerialName("detected_count") val detectedCount: Int? = null,     // 걷기: 걸음 수
    @SerialName("duration_sec") val durationSec: Int? = null,
    @SerialName("motion_score") val motionScore: Float? = null,
)

@Serializable
data class SensorSessionCreateResponse(
    @SerialName("sensor_session_id") val sensorSessionId: Int,
    @SerialName("recognition_status") val recognitionStatus: String,
)

// [요청 일부] 걷기 완료 상세. duration_min 은 필수.
@Serializable
data class WalkingDetail(
    @SerialName("duration_min") val durationMin: Float,
    @SerialName("distance_km") val distanceKm: Float? = null,
    val steps: Int? = null,
)

// [요청] 미션 완료(PATCH). 걷기는 walking_detail 만 채운다.
// ⚠️ success 를 반드시 보낸다(누락 시 서버가 실패로 처리할 수 있음).
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MissionLogUpdateRequest(
    val success: Boolean,
    // 기본값이 있어도 encodeDefaults=false 때문에 빠지지 않도록 항상 직렬화.
    // (계약: 완료 status="completed" 는 반드시 전송)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val status: String = "completed",
    @SerialName("walking_detail") val walkingDetail: WalkingDetail? = null,
)

@Serializable
data class MissionLogUpdateResponse(
    @SerialName("mission_log_id") val missionLogId: Int,
    val status: String,
    val success: Boolean,
    @SerialName("counted_for_daily") val countedForDaily: Boolean,
    @SerialName("daily_result") val dailyResult: String,
    @SerialName("sync_status") val syncStatus: String,
    @SerialName("daily_total_min") val dailyTotalMin: Float? = null,   // 걷기: 같은 날 서버 자동 합산값
)

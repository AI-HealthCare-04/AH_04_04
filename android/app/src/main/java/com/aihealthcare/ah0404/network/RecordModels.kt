package com.aihealthcare.ah0404.network

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    // 근육 건강 점수(#기록탭 §3, #272/#273). 코호트표 미탑재·65세 미만이면 null.
    @SerialName("muscle_score") val muscleScore: Int? = null,
    @SerialName("score_band") val scoreBand: String? = null,
    // 이 점수가 어느 코호트표 기준인지(#273). 추이에서 버전이 바뀐 지점은 비교 불가 경계로 취급한다(리뷰 #275-②).
    @SerialName("cohort_version") val cohortVersion: String? = null,
    @SerialName("change_percentage_points") val changePercentagePoints: Double? = null,
    @SerialName("comparison_status") val comparisonStatus: String = "baseline",
)

@Serializable
data class RiskHistoryResponse(
    /** 서버 계약에 따라 오래된 기록부터 최신 기록 순서로 온다. */
    val predictions: List<RiskHistoryItem> = emptyList(),
)

/** GET /risk-predictions/me/latest — 근육 건강 점수 최신값(#기록탭 §3). 필요한 필드만(ignoreUnknownKeys). */
@Serializable
data class RiskLatestResponse(
    // 피드백(#357)의 노출 정책 키 — 새 예측(prediction_id)당 1회만 묻는다.
    @SerialName("prediction_id") val predictionId: Int = 0,
    @SerialName("muscle_score") val muscleScore: Int? = null,
    @SerialName("score_band") val scoreBand: String? = null,
    @SerialName("cohort_version") val cohortVersion: String? = null,
    // 점수 기여도(#406): 바꿀 수 있는 근력·걷기·허리만. 구버전 서버면 빈 목록(ignoreUnknownKeys).
    val contributions: List<ContributionItemDto> = emptyList(),
)

/**
 * 근육 점수 SHAP 기여(#406). feature=musc_days|walk_days|waist_cm(백엔드가 이 3개만 준다).
 * effectOnScore = 점수 방향 기여(log-odds). 양수=점수를 올리는 방향, 음수=내리는(개선 여지) 방향.
 * 화면은 |값|으로 막대 길이를, 부호로 색을 정한다.
 */
@Serializable
data class ContributionItemDto(
    val feature: String,
    @SerialName("effect_on_score") val effectOnScore: Double = 0.0,
)

/**
 * PUT /risk-predictions/{prediction_id}/feedback (#357). 예측 결과 체감 피드백 —
 * response 는 similar / unsure / different. 서버 계약이 멱등이라 재시도에 안전하다.
 */
@Serializable
data class PredictionFeedbackRequest(
    val response: String,
)

/**
 * POST /risk-predictions/reassess 요청. 서버가 최신 프로필 + 최근 [activityWindowDays]일의 실제
 * 활동 로그(걷기/근력 일수)로 새 스냅샷을 만들어 **새 예측을 생성·저장**한다(서버 계약: 7 또는 14만 허용).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RiskReassessRequest(
    // ⚠️ 기본값 필드는 encodeDefaults=false(NetworkClient 기본)에서 JSON 에서 **생략**된다.
    //   서버 pydantic 은 이 필드가 필수(Literal[7,14])라 생략 시 422 — 실기기 QA 에서 내 정보 저장 후
    //   재평가가 전부 422 로 죽던 원인. ALWAYS 로 강제 직렬화한다(MissionLogUpdateRequest.status 와 동일 패턴).
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("activity_window_days") val activityWindowDays: Int = 7,
)

/** POST /risk-predictions/reassess 응답 중 필요한 필드만(ignoreUnknownKeys). */
@Serializable
data class RiskReassessResponse(
    @SerialName("prediction_id") val predictionId: Int = 0,
    @SerialName("muscle_score") val muscleScore: Int? = null,
    @SerialName("score_band") val scoreBand: String? = null,
    // 하루 1회 정책(#396). false = 오늘 이미 계산해 **기존 예측을 그대로** 돌려준 것 —
    //   점수가 있다고 새로 계산된 게 아니다(#388).
    @SerialName("recalculated") val recalculated: Boolean = true,
    // 다음 재평가 가능 시각(다음 KST 자정). 구버전 서버 호환을 위해 기본 null.
    @SerialName("next_available_at") val nextAvailableAt: String? = null,
)

/** what-if 점수 시뮬레이션(#기록탭 §4). score=null 인 지점은 점수 미제공. */
@Serializable
data class ScoreSimPointDto(val days: Int, val score: Int? = null)

@Serializable
data class ScoreSimulationResponse(
    val walk: List<ScoreSimPointDto> = emptyList(),
    val musc: List<ScoreSimPointDto> = emptyList(),
    @SerialName("cohort_version") val cohortVersion: String? = null,
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

// ── 5STS 측정 이력(#353, GET /physical-assessments/me/history) ────────────────
//   측정 기록(시간 존재)만 내려온다 — 스킵 기록은 추이에 안 쓰므로 서버가 제외.
//   비의료(#57): 시간·유형·시각 사실만. 판정 필드 없음.

@Serializable
data class StsAssessmentItem(
    @SerialName("physical_assessment_id") val physicalAssessmentId: Int,
    @SerialName("assessment_type") val assessmentType: String = "initial",
    @SerialName("chair_stand_5_time_sec") val chairStand5TimeSec: Double,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class StsHistoryResponse(
    val assessments: List<StsAssessmentItem> = emptyList(),
)

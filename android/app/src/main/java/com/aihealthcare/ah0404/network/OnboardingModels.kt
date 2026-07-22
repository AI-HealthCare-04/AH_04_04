package com.aihealthcare.ah0404.network

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ============================================================================
 *  온보딩 백엔드 계약 DTO — BACKEND_ONBOARDING_CONTRACT.md(dev 정본, #57) 기준.
 * ============================================================================
 *  ⚠️ 직렬화 주의: NetworkClient 의 Json 은 encodeDefaults=false 다.
 *     - "필수인데 상수 기본값"(input_method, has_estimated_value 등)은 기본값이면 전송에서
 *       빠져 422 가 나므로 @EncodeDefault(ALWAYS) 로 항상 직렬화한다.
 *     - "선택(생략 시 서버 기본)"(session_id/waist_cm/kidney_status 등)은 null 기본으로 두면
 *       전송에서 빠지고 서버가 기본값(unknown 등)을 적용한다 — 의도된 동작.
 *  응답의 datetime 은 KST(+09:00) 문자열, 소수(bmi/height_cm 등)는 number(Double).
 * ============================================================================
 */

// ── 0) 인증 ────────────────────────────────────────────────────────────────
@Serializable
data class OnbUser(
    @SerialName("user_id") val userId: Int,
    val nickname: String,
    @SerialName("onboarding_status") val onboardingStatus: String,
    @SerialName("is_guest") val isGuest: Boolean = false,
)

@Serializable
data class AuthResponse(
    val user: OnbUser,
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("is_new_user") val isNewUser: Boolean = false,
)

@Serializable
data class SocialLoginRequest(
    @SerialName("id_token") val idToken: String,
    val nonce: String,
)

// ── 2) 약관 목록 ──────────────────────────────────────────────────────────
@Serializable
data class Term(
    @SerialName("terms_type") val termsType: String,
    val version: String,
    @SerialName("is_required") val isRequired: Boolean = false,
    val title: String? = null,
    val url: String? = null,
)

@Serializable
data class TermsListResponse(val terms: List<Term>)

// ── 3) 약관 동의 (200 OK) ─────────────────────────────────────────────────
// 필수 약관(service·privacy·sensitive_health) 전부 agreed=true 아니면 400. 빈 배열 422.
@Serializable
data class Agreement(
    @SerialName("terms_type") val termsType: String,
    val version: String,
    val agreed: Boolean,
)

@Serializable
data class AgreementsRequest(val agreements: List<Agreement>)

@Serializable
data class AgreementsResponse(
    @SerialName("onboarding_status") val onboardingStatus: String,
)

// ── 4) 건강체크 세션 ──────────────────────────────────────────────────────
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SessionCreateRequest(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("input_method") val inputMethod: String = "form",
)

@Serializable
data class SessionResponse(
    @SerialName("session_id") val sessionId: Int,
    val status: String,
    @SerialName("input_method") val inputMethod: String? = null,
    @SerialName("has_estimated_value") val hasEstimatedValue: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

// 7-대체) 체력검사 스킵 → completed
@Serializable
data class SkipResponse(
    @SerialName("session_id") val sessionId: Int,
    val status: String,
    @SerialName("onboarding_status") val onboardingStatus: String,
    @SerialName("activity_profile") val activityProfile: ActivityProfile? = null,
)

// ── 5) 건강 프로필 ────────────────────────────────────────────────────────
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class HealthProfileRequest(
    // 필수(자연 기본값 없음)
    @SerialName("birth_date") val birthDate: String,
    val sex: String,
    @SerialName("height_cm") val heightCm: Double,
    @SerialName("weight_kg") val weightKg: Double,
    @SerialName("walking_practice") val walkingPractice: Boolean,
    @SerialName("strength_exercise") val strengthExercise: Boolean,
    // 필수(상수 기본값) — 항상 전송
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("activity_input_source") val activityInputSource: String = "self_report",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("input_method") val inputMethod: String = "form",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("has_estimated_value") val hasEstimatedValue: Boolean = false,
    // 선택(생략 시 서버 기본 적용)
    @SerialName("session_id") val sessionId: Int? = null,
    @SerialName("waist_cm") val waistCm: Double? = null,
    @SerialName("kidney_status") val kidneyStatus: String? = null,
    @SerialName("protein_restriction_status") val proteinRestrictionStatus: String? = null,
)

@Serializable
data class HealthProfileResponse(
    @SerialName("profile_id") val profileId: Int,
    val bmi: Double,
    @SerialName("protein_challenge_allowed") val proteinChallengeAllowed: Boolean,
)

// ── 6) 기초체력검사 (스킵 상호배제: 위반 시 422) ──────────────────────────────
// chair_stand_skipped=false → chair_stand_5_time_sec 필수 / =true → 생략
// 밴드는 5STS 단독(#102). 6m 걷기는 미구현·제외(#109)라 요청에서 전송하지 않는다.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PhysicalAssessmentRequest(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("assessment_type") val assessmentType: String = "initial",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("chair_stand_skipped") val chairStandSkipped: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("pain_reported") val painReported: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("dizziness_reported") val dizzinessReported: Boolean = false,
    @SerialName("session_id") val sessionId: Int? = null,
    @SerialName("chair_stand_5_time_sec") val chairStand5TimeSec: Double? = null,
)

@Serializable
data class ActivityProfile(
    @SerialName("current_level") val currentLevel: String,
    // physical-assessment 응답엔 있으나 home.activity_profile 엔 없음(홈은 current_level 만) → nullable.
    @SerialName("level_reason") val levelReason: String? = null,
)

@Serializable
data class PhysicalAssessmentResponse(
    @SerialName("physical_assessment_id") val physicalAssessmentId: Int,
    @SerialName("used_for_level_setting") val usedForLevelSetting: Boolean? = null,
    @SerialName("activity_profile") val activityProfile: ActivityProfile,
)

// ── 7) 위험도 예측 → completed ────────────────────────────────────────────
@Serializable
data class RiskPredictionRequest(
    @SerialName("profile_id") val profileId: Int,
)

@Serializable
data class RiskPredictionResponse(
    @SerialName("prediction_id") val predictionId: Int,
    @SerialName("profile_id") val profileId: Int? = null,
    @SerialName("model_variant") val modelVariant: String? = null,
    @SerialName("care_stage") val careStage: String,          // good | maintain | action_needed
    @SerialName("display_message") val displayMessage: String,
    val disclaimer: String? = null,
    @SerialName("onboarding_status") val onboardingStatus: String,
)

// ── 8) 홈 (온보딩 완료 판정: latest_prediction 노출 확인) ─────────────────────
// 타입 확정 HomeResponse 는 HomeModels.kt 로 이관(홈 UI 롤에서 확정). getHome() 은 그것을 공유한다.

// ── enum 허용값(요청 문자열은 이것만) — BACKEND_ONBOARDING_CONTRACT.md §3 ──────
object OnbEnums {
    val SEX = listOf("male", "female")
    val KIDNEY_STATUS = listOf("none", "kidney_disease", "dialysis", "unknown")
    val PROTEIN_RESTRICTION_STATUS = listOf("none", "restricted", "unknown")
    val ACTIVITY_INPUT_SOURCE = listOf("self_report", "service_log")
    val INPUT_METHOD = listOf("form", "service_log", "sensor", "manual")
    val ASSESSMENT_TYPE = listOf("initial", "reassessment")
    val ACTIVITY_LEVEL = listOf("easy", "normal", "hard")
    val LEVEL_REASON = listOf("initial_test", "rule", "llm_recommendation", "user_selected")
    val ONBOARDING_STATUS = listOf("pending", "terms_agreed", "profile_required", "completed")
    const val COMPLETED = "completed"
}

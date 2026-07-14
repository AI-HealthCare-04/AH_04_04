package com.aihealthcare.ah0404.onboarding

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aihealthcare.ah0404.network.Agreement
import com.aihealthcare.ah0404.network.AgreementsRequest
import com.aihealthcare.ah0404.network.HealthProfileRequest
import com.aihealthcare.ah0404.network.OnboardingApi
import com.aihealthcare.ah0404.network.PhysicalAssessmentRequest
import com.aihealthcare.ah0404.network.RiskPredictionRequest
import com.aihealthcare.ah0404.network.RiskPredictionResponse
import com.aihealthcare.ah0404.network.Term
import com.aihealthcare.ah0404.network.TokenHolder
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.launch

/** 온보딩 단계. 화면 라우팅의 기준. */
enum class OnbStep { WELCOME, TERMS, PROFILE, ASSESSMENT, RESULT }

/**
 * 온보딩 흐름 상태머신 + 백엔드 배선.
 *
 *  실제 OnboardingApi(#58) 를 호출하되, 백엔드가 없을 때는 목업 값으로 폴백해 화면 흐름을
 *  항상 검증할 수 있게 한다(역할분담 문서 §3-정인: "동적은 mock 바인딩, 실제 API 는 스위치만").
 *  폴백이 일어나면 mockMode=true 로 표시.
 */
class OnboardingViewModel(
    private val api: OnboardingApi = retrofit.create(OnboardingApi::class.java),
) : ViewModel() {

    var step by mutableStateOf(OnbStep.WELCOME); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var mockMode by mutableStateOf(false); private set

    // 약관
    var terms by mutableStateOf<List<Term>>(emptyList()); private set
    var agreed by mutableStateOf<Set<String>>(emptySet()); private set

    // 프로필 입력값 (구조화 → 탭/숫자 입력)
    var birthYear by mutableStateOf("")
    var birthMonth by mutableStateOf("")
    var birthDay by mutableStateOf("")
    var sex by mutableStateOf<String?>(null)
    var heightCm by mutableStateOf("")
    var weightKg by mutableStateOf("")
    var waistCm by mutableStateOf("")
    var walkingPractice by mutableStateOf<Boolean?>(null)
    var strengthExercise by mutableStateOf<Boolean?>(null)
    var kidneyStatus by mutableStateOf("unknown")
    var proteinStatus by mutableStateOf("unknown")

    // 결과
    private var sessionId: Int? = null
    private var profileId: Int? = null
    var bmi by mutableStateOf<Double?>(null); private set
    var result by mutableStateOf<RiskPredictionResponse?>(null); private set

    private val requiredTerms = listOf("service", "privacy", "sensitive_health")

    /** S0 → 게스트 로그인 후 약관 목록 로드. */
    fun start() = launchStep("시작") {
        val auth = runCatching { api.guestLogin() }.getOrNull()
        if (auth != null) {
            TokenHolder.token = auth.accessToken
        } else {
            enterMock()
        }
        terms = runCatching { api.getTerms().terms }.getOrNull() ?: run {
            enterMock(); defaultTerms()
        }
        step = OnbStep.TERMS
    }

    fun toggleAgree(termsType: String) {
        agreed = if (agreed.contains(termsType)) agreed - termsType else agreed + termsType
    }

    fun agreeAll() {
        agreed = terms.map { it.termsType }.toSet()
    }

    val allRequiredAgreed: Boolean
        get() = requiredTerms.all { req -> agreed.contains(req) }

    /** S1 → 약관 동의 전송 후 세션 생성, 프로필 단계로. */
    fun submitAgreements() = launchStep("약관 동의") {
        if (!allRequiredAgreed) {
            error = "필수 약관에 모두 동의해 주세요."
            return@launchStep
        }
        val body = AgreementsRequest(
            terms.map { Agreement(it.termsType, it.version, agreed.contains(it.termsType)) },
        )
        runCatching { api.agreeTerms(body) }.onFailure { enterMock() }
        sessionId = runCatching { api.createSession().sessionId }.getOrNull() ?: run { enterMock(); null }
        step = OnbStep.PROFILE
    }

    /** S3 → 건강 프로필 저장 후 체력검사 단계로. */
    fun submitProfile() = launchStep("프로필 저장") {
        val birth = composeBirthDate() ?: run {
            error = "생년월일을 정확히 입력해 주세요."; return@launchStep
        }
        val h = heightCm.toDoubleOrNull()
        val w = weightKg.toDoubleOrNull()
        if (sex == null || h == null || w == null || walkingPractice == null || strengthExercise == null) {
            error = "키·몸무게·성별·운동 여부를 모두 입력해 주세요."; return@launchStep
        }
        val body = HealthProfileRequest(
            birthDate = birth,
            sex = sex!!,
            heightCm = h,
            weightKg = w,
            walkingPractice = walkingPractice!!,
            strengthExercise = strengthExercise!!,
            sessionId = sessionId,
            waistCm = waistCm.toDoubleOrNull(),
            kidneyStatus = kidneyStatus,
            proteinRestrictionStatus = proteinStatus,
        )
        val resp = runCatching { api.createHealthProfile(body) }.getOrNull()
        if (resp != null) {
            profileId = resp.profileId
            bmi = resp.bmi
        } else {
            enterMock()
            bmi = if (h > 0) w / ((h / 100) * (h / 100)) else null
        }
        step = OnbStep.ASSESSMENT
    }

    /** S4 → 체력검사 건너뛰고 결과로. */
    fun skipAssessment() = launchStep("체력검사 건너뛰기") {
        sessionId?.let { sid -> runCatching { api.skipHealthCheck(sid) } }
        predictAndFinish()
    }

    /** S4 → 체력검사 값 제출 후 결과로. */
    fun submitAssessment(chairStandSec: Double?, walk6mSec: Double?) = launchStep("체력검사 제출") {
        val body = PhysicalAssessmentRequest(
            chairStandSkipped = chairStandSec == null,
            walk6mSkipped = walk6mSec == null,
            chairStand5TimeSec = chairStandSec,
            walk6mTimeSec = walk6mSec,
            sessionId = sessionId,
        )
        runCatching { api.createPhysicalAssessment(body) }.onFailure { enterMock() }
        predictAndFinish()
    }

    private suspend fun predictAndFinish() {
        val pid = profileId
        result = if (pid != null) {
            runCatching { api.createRiskPrediction(RiskPredictionRequest(pid)) }.getOrNull()
        } else {
            null
        } ?: run { enterMock(); mockResult() }
        step = OnbStep.RESULT
    }

    fun dismissError() { error = null }

    // ── 내부 유틸 ──────────────────────────────────────────────
    private fun launchStep(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            loading = true
            error = null
            runCatching { block() }
                .onFailure {
                    Log.w(TAG, "$label 실패: ${it.message}")
                    error = "$label 중 문제가 발생했어요. 잠시 후 다시 시도해 주세요."
                }
            loading = false
        }
    }

    private fun enterMock() { mockMode = true }

    private fun composeBirthDate(): String? {
        val y = birthYear.toIntOrNull() ?: return null
        val m = birthMonth.toIntOrNull() ?: return null
        val d = birthDay.toIntOrNull() ?: return null
        if (y !in 1900..2025 || m !in 1..12 || d !in 1..31) return null
        return "%04d-%02d-%02d".format(y, m, d)
    }

    private fun defaultTerms(): List<Term> = listOf(
        Term("service", "1.0", isRequired = true, title = "서비스 이용약관"),
        Term("privacy", "1.0", isRequired = true, title = "개인정보 처리방침"),
        Term("sensitive_health", "1.0", isRequired = true, title = "민감정보(건강) 수집·이용 동의"),
        Term("marketing", "1.0", isRequired = false, title = "마케팅 정보 수신 동의(선택)"),
    ).also { terms = it }

    private fun mockResult() = RiskPredictionResponse(
        predictionId = 0,
        careStage = "maintain",
        displayMessage = "지금처럼 꾸준히 이어가면 좋아요. 오늘도 가볍게 걷기부터 시작해 볼까요?",
        disclaimer = null,
        onboardingStatus = "completed",
    )

    companion object {
        const val TAG = "Onboarding"
    }
}

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
 *  ⚠️ 리뷰 #63(지영 P1-1) 반영: **API 실패를 목업 완료로 처리하지 않는다.**
 *     각 단계는 실제 OnboardingApi(#58)를 호출하고, 실패 시 예외가 launchStep 에서 잡혀
 *     에러 안내 + 재시도가 되며 **다음 단계로 진행하지 않는다**(서버 미저장 상태로 완료되는 것 방지).
 *     오프라인 화면 확인은 Welcome 의 debug 전용 "둘러보기(데모)"로 대체한다.
 */
class OnboardingViewModel(
    private val api: OnboardingApi = retrofit.create(OnboardingApi::class.java),
) : ViewModel() {

    var step by mutableStateOf(OnbStep.WELCOME); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

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
        TokenHolder.token = api.guestLogin().accessToken
        terms = api.getTerms().terms
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
        api.agreeTerms(body)
        sessionId = api.createSession().sessionId
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
        val resp = api.createHealthProfile(body)
        profileId = resp.profileId
        bmi = resp.bmi
        step = OnbStep.ASSESSMENT
    }

    /** S4 → 체력검사 건너뛰고 결과로. */
    fun skipAssessment() = launchStep("체력검사 건너뛰기") {
        sessionId?.let { sid -> api.skipHealthCheck(sid) }
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
        api.createPhysicalAssessment(body)
        predictAndFinish()
    }

    /** 위험도 예측 → 결과. profileId 가 없으면(비정상) 예외로 에러 처리. */
    private suspend fun predictAndFinish() {
        val pid = profileId ?: throw IllegalStateException("프로필 정보가 없습니다. 프로필부터 다시 진행해 주세요.")
        result = api.createRiskPrediction(RiskPredictionRequest(pid))
        step = OnbStep.RESULT
    }

    fun dismissError() { error = null }

    // ── 내부 유틸 ──────────────────────────────────────────────
    // 실패 시 error 를 설정하고 step 은 그대로 둔다(재시도는 사용자가 같은 버튼을 다시 눌러 수행).
    private fun launchStep(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            loading = true
            error = null
            runCatching { block() }
                .onFailure {
                    Log.w(TAG, "$label 실패: ${it.message}")
                    error = "$label 중 문제가 발생했어요. 네트워크를 확인하고 다시 시도해 주세요."
                }
            loading = false
        }
    }

    private fun composeBirthDate(): String? {
        val y = birthYear.toIntOrNull() ?: return null
        val m = birthMonth.toIntOrNull() ?: return null
        val d = birthDay.toIntOrNull() ?: return null
        if (y !in 1900..2025 || m !in 1..12 || d !in 1..31) return null
        return "%04d-%02d-%02d".format(y, m, d)
    }

    companion object {
        const val TAG = "Onboarding"
    }
}

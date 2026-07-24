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

internal fun previousOnboardingStep(step: OnbStep): OnbStep? = when (step) {
    OnbStep.TERMS -> OnbStep.WELCOME
    OnbStep.PROFILE -> OnbStep.TERMS
    OnbStep.ASSESSMENT -> OnbStep.PROFILE
    OnbStep.WELCOME,
    OnbStep.RESULT,
    -> null
}

/**
 * 키·몸무게 '모름' 시 성별·연령대 추정치 (cm, kg). 상수 표 — 나중에 교체 가능.
 *   남 65–74: 166/65 · 75+: 163/62   /   여 65–74: 153/56 · 75+: 150/53
 * 성별 미선택/미상은 남성 기준으로 폴백(성별 선택 후 다시 '모름' 누르면 갱신).
 */
internal fun estimateBody(sex: String?, age: Int?): Pair<Int, Int> {
    val is75plus = age != null && age >= 75
    return when (sex) {
        "female" -> if (is75plus) 150 to 53 else 153 to 56
        else -> if (is75plus) 163 to 62 else 166 to 65
    }
}

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
    // '모름' 추정치의 만 나이 판정용 오늘 날짜(테스트에서 고정 주입). Calendar.MONTH 는 0-based → +1.
    private val todayYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
    private val todayMonth: Int = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1,
    private val todayDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH),
) : ViewModel() {

    var step by mutableStateOf(OnbStep.WELCOME); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    /**
     * 이 온보딩이 게스트('체험으로 시작하기')인가(#153). 완료 시 영속화 여부를 가른다:
     *   게스트면 토큰·완료 플래그를 디스크에 남기지 않는다(한 폰 다인 시연 잔존 방지).
     */
    var isGuest by mutableStateOf(false); private set

    // 약관
    var terms by mutableStateOf<List<Term>>(emptyList()); private set
    var agreed by mutableStateOf<Set<String>>(emptySet()); private set

    // 프로필 입력값 (구조화 → 탭/숫자 입력)
    var birthYear by mutableStateOf("")
    var birthMonth by mutableStateOf("")
    var birthDay by mutableStateOf("")
    var sex by mutableStateOf<String?>(null)
    // 키·몸무게: 실제 입력값은 heightCm/weightKg(수동), '모름'이면 플래그만 세우고 값은 표시/제출 시점에
    //   최종 성별·생년월일로 매번 재계산한다(리뷰 #75-2 입력 순서 의존성 제거).
    var heightCm by mutableStateOf(""); private set
    var weightKg by mutableStateOf(""); private set
    var waistCm by mutableStateOf("")
    var heightEstimated by mutableStateOf(false); private set
    var weightEstimated by mutableStateOf(false); private set

    /** 키·몸무게 중 하나라도 '모름'(추정)이면 true → has_estimated_value 로 전송. */
    val hasEstimatedValue: Boolean get() = heightEstimated || weightEstimated

    /**
     * '모름'은 유효한 성별·생년월일 + **만 65세 이상**일 때만 허용(리뷰 #75-2·#75-4).
     * 추정표·위험도 모델 모두 65세 이상 대상이라, 64세 이하엔 고령 추정치를 넣지 않는다.
     */
    val canEstimate: Boolean get() = sex != null && (ageYears() ?: 0) >= MIN_SUPPORTED_AGE

    /** 화면 표시값: 추정이면 현재 성별·나이로 라이브 계산(성별/생일 바꾸면 즉시 갱신), 아니면 수동 입력값. */
    val heightInput: String get() = if (heightEstimated) estimateBody(sex, ageYears()).first.toString() else heightCm
    val weightInput: String get() = if (weightEstimated) estimateBody(sex, ageYears()).second.toString() else weightKg

    /** 사용자가 직접 입력 → 실제값이므로 추정 플래그 해제. */
    fun setHeight(value: String) { heightCm = value; heightEstimated = false }
    fun setWeight(value: String) { weightKg = value; weightEstimated = false }

    /** '모름' → 추정 플래그만 세운다(값은 표시/제출 시 최종 인구통계로 재계산). 유효 성별·생일 없으면 무시. */
    fun markHeightUnknown() { if (canEstimate) { heightEstimated = true; heightCm = "" } }
    fun markWeightUnknown() { if (canEstimate) { weightEstimated = true; weightKg = "" } }

    /** 허리둘레 '모름' → 비워서 요청 body 에서 생략(백엔드 선택 처리). */
    fun markWaistUnknown() { waistCm = "" }

    /** 만 나이(월/일 반영): 올해 생일이 아직 안 지났으면 -1(리뷰 #75-3 경계 오차 제거). */
    private fun ageYears(): Int? {
        val y = birthYear.toIntOrNull() ?: return null
        val m = birthMonth.toIntOrNull() ?: return null
        val d = birthDay.toIntOrNull() ?: return null
        var age = todayYear - y
        if (todayMonth < m || (todayMonth == m && todayDay < d)) age -= 1
        return age
    }
    var walkingPractice by mutableStateOf<Boolean?>(null)
    var strengthExercise by mutableStateOf<Boolean?>(null)
    var kidneyStatus by mutableStateOf("unknown")
    var proteinStatus by mutableStateOf("unknown")
    var chairStandSec by mutableStateOf("")

    // 결과
    private var sessionId: Int? = null
    private var profileId: Int? = null
    private var lastSubmittedProfile: HealthProfileRequest? = null
    var bmi by mutableStateOf<Double?>(null); private set
    var result by mutableStateOf<RiskPredictionResponse?>(null); private set

    private val requiredTerms = listOf("service", "privacy", "sensitive_health")

    /** S0 → 체험 사용자의 게스트 로그인 후 약관 목록 로드. 기존 소셜 토큰은 덮어쓰지 않는다. */
    fun start() = launchStep("시작") {
        isGuest = true // 게스트 온보딩 — 완료해도 디스크에 안 남긴다(#153).
        if (TokenHolder.token.isBlank()) {
            TokenHolder.token = api.guestLogin().accessToken
        }
        loadTerms()
    }

    /** 소셜 로그인(미완료 계정) 성공 후 같은 온보딩 흐름을 이어간다. 완료 시 영속화 대상(#153). */
    fun continueAuthenticated() = launchStep("로그인") {
        isGuest = false
        loadTerms()
    }

    /**
     * 이전 온보딩 잔여 상태를 시작(WELCOME)으로 초기화한다(#153 후속 — 무한루프 방지).
     *
     * WELCOME 이후 단계는 토큰(게스트/소셜)이 있어야 도달한다. 그런데 로그아웃·세션리셋으로 토큰이
     * 사라진 채 이 VM(Activity 수명)에 이전 step(예: RESULT)이 남으면, '홈으로 시작하기'가 토큰 없는
     * 완료로 처리돼 라우팅이 LOGIN_REQUIRED 로 튕기고, 리셋하면 다시 그 stale 화면이 떠 무한루프가 난다.
     * 화면 진입 시 '토큰 없음 + step≠WELCOME' 이면 호출해 한 폰 다인 시연의 이전 입력(PII 포함)까지 비운다.
     */
    fun resetToWelcome() {
        step = OnbStep.WELCOME
        error = null
        isGuest = false
        terms = emptyList()
        agreed = emptySet()
        sessionId = null
        profileId = null
        bmi = null
        result = null
        birthYear = ""; birthMonth = ""; birthDay = ""
        sex = null
        heightCm = ""; weightKg = ""; waistCm = ""
        heightEstimated = false; weightEstimated = false
        walkingPractice = null; strengthExercise = null
        kidneyStatus = "unknown"; proteinStatus = "unknown"
        chairStandSec = ""
        lastSubmittedProfile = null
    }

    private suspend fun loadTerms() {
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
        if (sessionId == null) {
            sessionId = api.createSession().sessionId
        }
        step = OnbStep.PROFILE
    }

    /** S3 → 건강 프로필 저장 후 체력검사 단계로. */
    fun submitProfile() = launchStep("프로필 저장") {
        val birth = composeBirthDate() ?: run {
            error = "생년월일을 정확히 입력해 주세요."; return@launchStep
        }
        // 지원 대상: 만 65세 이상(추정표·위험도 모델 모두 65+ 기준, 리뷰 #75-4).
        val age = ageYears()
        if (age == null || age < MIN_SUPPORTED_AGE) {
            error = "이 서비스는 만 65세 이상 어르신을 위한 것이에요. 생년월일을 확인해 주세요."; return@launchStep
        }
        // 추정('모름')이면 제출 시점의 최종 성별·나이로 계산(버튼 누른 시점 아님, 리뷰 #75-2).
        val estimate = if (hasEstimatedValue) estimateBody(sex, ageYears()) else null
        val h = if (heightEstimated) estimate!!.first.toDouble() else heightCm.toDoubleOrNull()
        val w = if (weightEstimated) estimate!!.second.toDouble() else weightKg.toDoubleOrNull()
        if (sex == null || h == null || w == null || walkingPractice == null || strengthExercise == null) {
            error = "키·몸무게·성별·운동 여부를 모두 입력해 주세요."; return@launchStep
        }
        // 양수 가드(재란 #75 nit): "0"/음수 수동 입력이 백엔드 gt=0 에서 422 나기 전에 막는다.
        if (h <= 0 || w <= 0) {
            error = "키·몸무게는 0보다 큰 값으로 입력해 주세요."; return@launchStep
        }
        val body = HealthProfileRequest(
            birthDate = birth,
            sex = sex!!,
            heightCm = h,
            weightKg = w,
            walkingPractice = walkingPractice!!,
            strengthExercise = strengthExercise!!,
            sessionId = sessionId,
            // 허리둘레는 양수일 때만 전송, 그 외(빈값·0·음수)는 생략(선택 필드).
            waistCm = waistCm.toDoubleOrNull()?.takeIf { it > 0 },
            kidneyStatus = kidneyStatus,
            proteinRestrictionStatus = proteinStatus,
            // 키·몸무게 중 하나라도 '모름' 추정치면 true(둘 다 실제 입력이면 false).
            hasEstimatedValue = hasEstimatedValue,
        )
        if (body != lastSubmittedProfile) {
            val resp = api.createHealthProfile(body)
            profileId = resp.profileId
            bmi = resp.bmi
            lastSubmittedProfile = body
        }
        step = OnbStep.ASSESSMENT
    }

    /** S4 → 체력검사 건너뛰고 결과로. */
    fun skipAssessment() = launchStep("체력검사 건너뛰기") {
        sessionId?.let { sid -> api.skipHealthCheck(sid) }
        predictAndFinish()
    }

    /** S4 → 체력검사 값 제출 후 결과로. 밴드는 5STS 단독(#102), 6m 미전송(#109). */
    fun submitAssessment(chairStandSec: Double?) = launchStep("체력검사 제출") {
        val body = PhysicalAssessmentRequest(
            chairStandSkipped = chairStandSec == null,
            chairStand5TimeSec = chairStandSec,
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

    /**
     * 입력값과 이미 저장된 서버 상태는 유지하고 화면 단계만 되돌린다.
     * RESULT는 세션이 완료된 상태이므로 화면 호스트에서 종료 확인을 담당한다.
     */
    fun goBack(): Boolean {
        if (loading) return false
        val previous = previousOnboardingStep(step) ?: return false
        error = null
        step = previous
        return true
    }

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

        /** 지원 최소 연령(만). 추정표·위험도 모델 모두 65세 이상 대상(리뷰 #75-4). */
        const val MIN_SUPPORTED_AGE = 65
    }
}

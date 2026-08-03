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
import com.aihealthcare.ah0404.network.SessionStore
import com.aihealthcare.ah0404.network.Term
import com.aihealthcare.ah0404.network.TokenHolder
import com.aihealthcare.ah0404.network.retrofit
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** 온보딩 단계. 화면 라우팅의 기준. 체력검사(ASSESSMENT) 제출/스킵 후엔 별도 결과화면 없이 홈으로 간다(#299). */
enum class OnbStep { WELCOME, TERMS, PROFILE, ASSESSMENT }

internal fun previousOnboardingStep(step: OnbStep): OnbStep? = when (step) {
    OnbStep.TERMS -> OnbStep.WELCOME
    OnbStep.PROFILE -> OnbStep.TERMS
    OnbStep.ASSESSMENT -> OnbStep.PROFILE
    OnbStep.WELCOME -> null
}

/** "검사 완료"로 제출 가능한 5STS 시간. 공백·숫자 아님·0 이하·비유한 값은 건너뛰기와 구분해 거부한다. */
internal fun parseChairStandSeconds(input: String): Double? =
    input.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

/** 추정값은 정수면 불필요한 `.0` 없이, 소수면 원래 정밀도로 입력칸에 표시한다. */
private fun Double.toInputText(): String = if (this % 1.0 == 0.0) toInt().toString() else toString()

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
    // 현재 인증 주체 키(#383). SessionStore.authRevision 은 private set 이라 테스트에서 못 바꾸므로
    //   ExerciseVideosViewModel 과 같은 방식으로 주입 가능하게 둔다.
    private val authKey: () -> Int = { SessionStore.authRevision },
    // 현재 세션이 소셜(비게스트)인가(#398). authKey 와 같은 이유로 주입 가능하게 둔다.
    private val socialAuth: () -> Boolean = { SessionStore.socialAuthenticated },
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
    // 허리둘레 '모름'을 눌렀는지. 값이 아니라 안내 문구 노출에만 쓴다 — 눌러도 화면이 안 바뀌어
    //   버튼이 고장 난 것처럼 보이던 문제(선택 항목이라 대개 이미 비어 있다). 직접 입력하면 해제된다.
    var waistSkipped by mutableStateOf(false)
    var heightEstimated by mutableStateOf(false); private set
    var weightEstimated by mutableStateOf(false); private set

    // 추정('모름')은 나이·성별에 종속된다 — '모름' 선택 후 생년월일을 추정 대상 미만(만 50세 미만)으로 바꾸면
    //   플래그(heightEstimated 등)만 남아 무효 추정값이 표시·제출될 수 있다(리뷰 #313). canEstimate 를 함께 확인해
    //   '유효한 추정'만 인정한다 — 무효 추정은 표시·제출·has_estimated_value 모두에서 무시하고 직접 입력을 유도한다.
    // 화면 표시("추정치로 입력했어요")·인라인 검증(heightError)도 원본 플래그가 아닌 이 유효 상태를 써야 무효
    //   추정이 남았을 때 표시·검증·제출이 일관된다(리뷰 #313 재리뷰 — 원본 플래그 사용 시 '추정치 입력' + '직접
    //   입력하세요'가 동시에 뜨는 모순). 그래서 public 으로 노출한다.
    val heightEstimatedValid: Boolean get() = heightEstimated && canEstimate
    val weightEstimatedValid: Boolean get() = weightEstimated && canEstimate

    /** 키·몸무게 중 하나라도 유효한 '모름'(추정)이면 true → has_estimated_value 로 전송. */
    val hasEstimatedValue: Boolean get() = heightEstimatedValid || weightEstimatedValid

    /**
     * '모름'은 유효한 성별·생년월일 + **만 50세 이상**일 때 허용(#298 C: 50~64 추정표 확장, 리뷰 #75-2).
     *  50세 미만은 추정 근거가 없어 직접 입력만 받는다(사유는 [estimateUnavailableReason] 로 안내).
     */
    // composeBirthDate() != null 을 함께 요구한다(리뷰 #313): ageYears() 는 숫자 변환만 하고 날짜 유효성(월 범위·
    //   실제 일수·미래)을 안 봐서, 1958-13-01·1958-02-30 같은 무효 날짜에도 canEstimate 가 참이 돼 birthDateError
    //   (가입 불가)와 '모름 추정 활성'이 동시에 뜬다. 유효한 생년월일일 때만 추정을 허용한다.
    val canEstimate: Boolean get() =
        sex != null && composeBirthDate() != null && (ageYears() ?: 0) >= MIN_ESTIMATE_AGE

    /**
     * '모름'(추정) 버튼이 비활성인 이유(#298 B). 활성이면 null. 성별·생년월일이 없으면 그 안내를,
     *  값은 있으나 추정 대상 연령(만 50세)에 못 미치면 직접 입력을 안내한다.
     */
    val estimateUnavailableReason: String?
        get() {
            if (canEstimate) return null
            return if (estimateBlockedByIncompleteDemographics) {
                "성별·생년월일을 먼저 입력하면 사용할 수 있어요"
            } else {
                "키·몸무게 추정은 만 50세 이상부터 제공해요. 정확한 값을 직접 입력해 주세요."
            }
        }

    /**
     * 추정 불가가 '성별·생년월일 미완성/무효' 때문인가(true) '유효 생년월일 + 실제 만 50세 미만' 때문인가(false)
     *  (#395 리뷰 P1). [estimateUnavailableReason] 과 [estimateInvalidatedNotice] 가 이 하나를 공유해 두 안내가
     *  어긋나지 않게 한다. canEstimate == true(추정 가능)면 의미 없음 — 호출부가 canEstimate/estimateInvalidated 를
     *  먼저 확인한다. (예: 56세 사용자가 생년월일 수정 중 월을 잠깐 비우면 age 는 계산 불가지만 만 50세 미만은 아니다.)
     */
    private val estimateBlockedByIncompleteDemographics: Boolean
        get() = sex == null || composeBirthDate() == null || ageYears() == null

    /**
     * '모름'으로 추정 플래그가 섰는데 그 뒤 생년월일을 만 50세 미만(또는 무효)으로 바꿔 **추정이 무효화된** 상태(#395).
     *  이때 값은 비어 있고(heightCm="") heightEstimatedValid 도 false 라, 예전엔 아무 신호 없이 입력칸만 비고
     *  '다음'이 죽어 "고장 난 것처럼" 보였다. 이 상태를 밖으로 노출해 강조 안내([estimateInvalidatedNotice])와
     *  입력칸 인라인 오류([heightError]/[weightError])로 신호를 준다. 데이터 규칙(#313)은 유지 — '다음'은 여전히
     *  직접 입력해야 활성화된다(선택지 A: 막힌 '느낌'만 없애고 규칙은 그대로).
     */
    val estimateInvalidated: Boolean get() = (heightEstimated || weightEstimated) && !canEstimate

    /**
     * 무효화된 추정을 알리는 강조 안내(#395 선택지 A). 일반 연령 안내([underAgeNotice])보다 구체적이라 화면에서 우선한다.
     *  값을 직접 채우면 사라진다. **막힌 이유를 나눠 안내한다**(리뷰 #409 P1):
     *   - 성별 미선택·생년월일 미완성/무효(입력 중간·1958-02-30·미래) → "만 50세 미만"으로 단정하지 않는다(실제로 50세+일 수 있다).
     *   - 유효 생년월일 + 실제 만 50세 미만 → 추정 근거가 없음을 알리고, 오타 되돌리기를 위해 생년월일 확인을 함께 유도.
     */
    val estimateInvalidatedNotice: String?
        get() {
            if (!estimateInvalidated) return null
            return if (estimateBlockedByIncompleteDemographics) {
                "생년월일을 바꾸셔서 추정치를 지웠어요. 성별·생년월일을 다시 확인하면 추정치를 쓸 수 있어요. 직접 입력하셔도 돼요."
            } else {
                "생년월일을 바꾸셔서 추정치를 지웠어요. 만 50세 미만은 추정을 제공하지 않으니 키·몸무게를 직접 입력해 주세요. 생년월일이 맞는지도 한 번 확인해 주세요."
            }
        }

    /** 화면 표시값: 추정이면 현재 성별·나이로 라이브 계산(성별/생일 바꾸면 즉시 갱신), 아니면 수동 입력값. */
    val heightInput: String get() = if (heightEstimatedValid) estimateBody(sex, ageYears()).first.toInputText() else heightCm
    val weightInput: String get() = if (weightEstimatedValid) estimateBody(sex, ageYears()).second.toInputText() else weightKg

    /** 키 인라인 검증 문구(#298 A-2). 직접 입력값이 현실 범위 밖이면 그 자리에서 안내(추정치·빈칸은 조용). */
    val heightError: String?
        get() {
            // '모름' 후 연령을 만 50세 미만으로 바꿔 추정이 무효화된 자리(#395): 값이 비어(heightCm="") 조용히
            //   null 이 되던 것을, 사라진 입력칸 자체가 신호를 내도록 오류로 잡는다(강조 안내와 함께).
            if (heightEstimated && !canEstimate) return "키를 직접 입력해 주세요"
            if (heightEstimatedValid || heightCm.isBlank()) return null
            val h = heightCm.toDoubleOrNull() ?: return "키를 숫자로 입력해 주세요"
            return if (h < HEIGHT_MIN_CM || h > HEIGHT_MAX_CM) {
                "키는 ${HEIGHT_MIN_CM.toInt()}~${HEIGHT_MAX_CM.toInt()}cm 사이로 입력해 주세요"
            } else {
                null
            }
        }

    /** 몸무게 인라인 검증 문구(#298 A-2). */
    val weightError: String?
        get() {
            // '모름' 후 추정 무효화 시 빈 입력칸이 신호를 내도록 오류로 잡는다(#395, heightError 와 동일).
            if (weightEstimated && !canEstimate) return "몸무게를 직접 입력해 주세요"
            if (weightEstimatedValid || weightKg.isBlank()) return null
            val w = weightKg.toDoubleOrNull() ?: return "몸무게를 숫자로 입력해 주세요"
            return if (w < WEIGHT_MIN_KG || w > WEIGHT_MAX_KG) {
                "몸무게는 ${WEIGHT_MIN_KG.toInt()}~${WEIGHT_MAX_KG.toInt()}kg 사이로 입력해 주세요"
            } else {
                null
            }
        }

    /** 사용자가 직접 입력 → 실제값이므로 추정 플래그 해제. */
    fun setHeight(value: String) { heightCm = value; heightEstimated = false }
    fun setWeight(value: String) { weightKg = value; weightEstimated = false }

    /** '모름' → 추정 플래그만 세운다(값은 표시/제출 시 최종 인구통계로 재계산). 유효 성별·생일 없으면 무시. */
    fun markHeightUnknown() { if (canEstimate) { heightEstimated = true; heightCm = "" } }
    fun markWeightUnknown() { if (canEstimate) { weightEstimated = true; weightKg = "" } }

    /** 허리둘레 '모름' → 비워서 요청 body 에서 생략(백엔드 선택 처리) + 안내 문구 노출 플래그. */
    fun markWaistUnknown() { waistCm = ""; waistSkipped = true }

    /** 만 나이(월/일 반영): 올해 생일이 아직 안 지났으면 -1(리뷰 #75-3 경계 오차 제거). */
    private fun ageYears(): Int? {
        val y = birthYear.toIntOrNull() ?: return null
        val m = birthMonth.toIntOrNull() ?: return null
        val d = birthDay.toIntOrNull() ?: return null
        var age = todayYear - y
        if (todayMonth < m || (todayMonth == m && todayDay < d)) age -= 1
        return age
    }
    // 활동 일수(#261): 예/아니오 boolean → 주당 일수. 걷기 0~7(국건영 BE3_31), 근력 0~5(BE5_1 top-coding).
    //   null = 미응답, 0 = "안 해요"(둘 다 유효한 답). 기본 0으로 시작하면 '안 만지고 넘긴 미응답'과 '주 0일'이
    //   합쳐져 예측 입력이 왜곡되므로(리뷰 #267 블로커) null 로 두고 submitProfile 에서 필수 응답을 강제한다.
    var walkDays by mutableStateOf<Int?>(null)
    var muscDays by mutableStateOf<Int?>(null)
    var kidneyStatus by mutableStateOf("unknown")
    var proteinStatus by mutableStateOf("unknown")
    var chairStandSec by mutableStateOf("")

    // 결과
    private var sessionId: Int? = null
    private var profileId: Int? = null
    private var lastSubmittedProfile: HealthProfileRequest? = null
    var bmi by mutableStateOf<Double?>(null); private set
    var result by mutableStateOf<RiskPredictionResponse?>(null); private set

    /**
     * 온보딩 완주 신호(#299). 체력검사 제출/스킵 → 예측 생성까지 끝나면 true. 화면 호스트가 이 값을 관찰해
     *  별도 결과화면 없이 곧장 홈(onComplete)으로 보낸다. RESULT 스텝을 없앴으므로 완료는 step 이 아니라 이 플래그로 알린다.
     *
     *  ⚠️ **일회성 이벤트**다(#383): 화면이 처리한 뒤 [consumeFinished] 로 즉시 내린다. 상태로 남겨 두면
     *  Activity 수명인 이 VM 에 신호가 계속 살아 있어, 다른 인증 주체로 온보딩 화면에 다시 들어왔을 때
     *  (탈퇴 → 같은 소셜 계정 재로그인 = 미완료 신규 계정) 약관·프로필을 건너뛰고 홈으로 직행한다.
     */
    var finished by mutableStateOf(false); private set

    /**
     * 완주 신호 소비(#383). 화면이 홈 라우팅을 처리한 직후 호출해 신호를 내린다 —
     * 이 VM 은 Activity 수명이라 신호가 남으면 다음 온보딩 진입에서 그대로 재발화한다.
     */
    fun consumeFinished() {
        finished = false
    }

    private val requiredTerms = listOf("service", "privacy", "sensitive_health")

    /**
     * 이 VM 이 들고 있는 진행 상태(step·입력값·sessionId·profileId)의 **주인**([SessionStore.authRevision]).
     * null = 아직 온보딩을 시작하지 않음.
     *
     * `finished` 만으로는 부족하다(#383 실기기 QA). 완주 신호는 홈 라우팅 직후 [consumeFinished] 로 소비되므로,
     * 완주 후 탈퇴 → 재로그인 경로에서는 **이미 false** 다. 그런데 재로그인으로 토큰은 있으니 기존 두 조건이
     * 모두 빗나가 리셋이 걸리지 않고, `step` 에 남은 ASSESSMENT 가 그대로 그려진다 — 새 계정이 약관·프로필을
     * 건너뛴 채 체력검사부터 시작하고, 이전 사용자의 입력값(PII)과 죽은 sessionId 까지 함께 남는다.
     *
     * 그래서 "신호가 남아 있는가" 대신 **"이 진행 상태가 지금 로그인한 사람의 것인가"** 를 본다.
     */
    private var progressOwner: Int? = null

    /**
     * 진행 상태의 주인을 지금 인증 주체로 확정한다. 온보딩 **시작점**([start]·[continueAuthenticated])에서만 부른다.
     *
     * 온보딩 도중의 정상 로그인(게스트 → 소셜)도 `authRevision` 을 올리지만, 그 경로는 반드시
     * [continueAuthenticated] 를 거치므로 여기서 주인이 갱신돼 stale 로 오판되지 않는다. 반대로 탈퇴 후
     * `LoginRequiredScreen` 에서의 재로그인은 이 두 시작점을 거치지 않아 주인이 갱신되지 않는다 — 그 차이가
     * '이어가는 로그인'과 '주체가 바뀐 재진입'을 가른다.
     */
    private fun claimProgress() {
        progressOwner = authKey()
    }

    /**
     * 남아 있는 진행 상태가 **다른 인증 주체**의 것인지(#383). 화면 진입 가드가 쓴다.
     * 아직 시작점을 거치지 않았으면(주인 없음) 되돌릴 진행도 없으므로 false.
     *
     * ⚠️ 예전엔 `step != WELCOME` 을 함께 봤는데 **틀린 가정이었다**(#398 실기기 QA).
     *   약관 화면에서 '이전'을 누르면 step 만 WELCOME 으로 가고 `agreed`·프로필 입력·sessionId 는 그대로
     *   남는다 — "WELCOME = 남은 진행 없음"이 성립하지 않는다. 주인이 다른지만 본다.
     */
    fun isProgressFromAnotherAuth(): Boolean {
        val owner = progressOwner ?: return false
        return owner != authKey()
    }

    /**
     * S0 → 체험 사용자의 게스트 로그인 후 약관 목록 로드. 기존 소셜 토큰은 덮어쓰지 않는다.
     *
     * ⚠️ 소셜 세션이 살아 있으면 이건 '체험'이 아니다(#398). 토큰이 있으면 게스트 로그인만 건너뛰고
     *   `isGuest = true` 는 그대로 세우던 탓에, 소셜 계정이 게스트로 취급돼 완주해도 영속화되지 않았다.
     *   소셜 토큰을 든 채 이 화면에 서는 경우가 실제로 있다 — 탈퇴 후 재로그인하면 진입 가드가
     *   WELCOME 으로 되돌린다(#383). 그때는 게스트로 시작하지 말고 인증된 흐름을 이어간다.
     */
    fun start() {
        if (socialAuth()) {
            continueAuthenticated()
            return
        }
        startAsGuest()
    }

    private fun startAsGuest() = launchStep("시작") {
        clearProgressIfAnotherAuth() // 다른 주체가 남긴 입력을 체험 온보딩이 물려받지 않게(#398)
        finished = false // 온보딩 시작점에서 완주 신호를 깐다 — stale finished 로 즉시 홈 라우팅되는 경로 원천 차단(리뷰 #311).
        isGuest = true // 게스트 온보딩 — 완료해도 디스크에 안 남긴다(#153).
        if (TokenHolder.token.isBlank()) {
            TokenHolder.token = api.guestLogin().accessToken
        }
        claimProgress() // 게스트 로그인은 SessionStore.applyLogin 을 타지 않아 authRevision 이 그대로다 — 현재 값을 주인으로.
        loadTerms()
    }

    /** 소셜 로그인(미완료 계정) 성공 후 같은 온보딩 흐름을 이어간다. 완료 시 영속화 대상(#153). */
    fun continueAuthenticated() = launchStep("로그인") {
        // ⚠️ 주인이 바뀐 진행은 **여기서 먼저 비운다**(#398 실기기 QA). 화면 진입 가드(#383)에 맡길 수 없다 —
        //   아래 claimProgress() 가 주인을 새로 찍는 순간 가드는 더 이상 stale 을 알아보지 못하고,
        //   그 뒤로는 이전 계정의 데이터가 **새 주인의 것으로 입양된다.**
        //   실제 증상: 탈퇴 후 재로그인하면 약관 화면까지는 갔는데 이전 계정의 동의 체크와 프로필 입력이
        //   그대로 남았다. loadTerms() 는 terms 만 갈아끼우고 agreed·sessionId·profileId·입력은 안 건드린다.
        clearProgressIfAnotherAuth()
        finished = false // 시작점에서 완주 신호 초기화(리뷰 #311) — resetToWelcome 을 안 거친 재진입도 방어.
        isGuest = false
        claimProgress() // applyLogin 으로 올라간 새 authRevision 을 주인으로 — 이어가는 로그인은 stale 이 아니다.
        loadTerms()
    }

    /**
     * 시작점 공통 전처리: 남은 진행의 주인이 지금 인증 주체와 다르면 비운다.
     * 주인을 찍기 **전에** 불러야 한다 — 찍고 나면 판별 근거가 사라진다.
     */
    private fun clearProgressIfAnotherAuth() {
        if (isProgressFromAnotherAuth()) resetToWelcome()
    }

    /**
     * 이전 온보딩 잔여 상태를 시작(WELCOME)으로 초기화한다(#153 후속 — 무한루프 방지).
     *
     * WELCOME 이후 단계는 토큰(게스트/소셜)이 있어야 도달한다. 그런데 로그아웃·세션리셋으로 토큰이
     * 사라진 채 이 VM(Activity 수명)에 이전 step(예: ASSESSMENT)이나 완주 신호(finished)가 남으면, 토큰 없는
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
        finished = false
        birthYear = ""; birthMonth = ""; birthDay = ""
        sex = null
        heightCm = ""; weightKg = ""; waistCm = ""
        heightEstimated = false; weightEstimated = false
        walkDays = null; muscDays = null
        kidneyStatus = "unknown"; proteinStatus = "unknown"
        chairStandSec = ""
        lastSubmittedProfile = null
        progressOwner = null // 진행이 비었으므로 주인도 없다 — 다음 시작점에서 다시 확정된다.
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
        // 최소 가입 연령 하한(리뷰 #313): 만 14세 미만은 개인정보보호법상 법정대리인 동의가 필요 → 제출 차단.
        //   (인라인 birthDateError 로도 안내하지만 제출 시점에도 최종 방어.) #298 의 50-64 예측 준비중 안내와는 별개.
        if ((ageYears() ?: 0) < MIN_SIGNUP_AGE) {
            error = "만 ${MIN_SIGNUP_AGE}세 이상만 가입할 수 있어요."; return@launchStep
        }
        // #298 C: 만 65세 미만도 가입·온보딩을 완료할 수 있다(예측만 "준비 중"). 나이 자체로 제출을 막지 않는다.
        //   생년월일 자체가 유효하면(composeBirthDate 통과) age 는 항상 산출된다.
        // 추정('모름')이면 제출 시점의 최종 성별·나이로 계산(버튼 누른 시점 아님, 리뷰 #75-2).
        // 유효한 추정만 반영한다(리뷰 #313): '모름' 후 연령을 50세 미만으로 바꾼 무효 추정은 여기서 값이 없어(빈칸)
        //   아래 '키·몸무게·성별 모두 입력' 검증에 걸려 거부된다 → 65–74 추정값이 50세 미만 프로필로 새지 않는다.
        val estimate = if (hasEstimatedValue) estimateBody(sex, ageYears()) else null
        val h = if (heightEstimatedValid) estimate!!.first else heightCm.toDoubleOrNull()
        val w = if (weightEstimatedValid) estimate!!.second else weightKg.toDoubleOrNull()
        if (sex == null || h == null || w == null) {
            error = "키·몸무게·성별을 모두 입력해 주세요."; return@launchStep
        }
        // 활동 일수는 필수 응답(리뷰 #267): '안 해요(0일)'도 사용자가 명시적으로 골라야 하며, 미응답(null)은 막는다.
        if (walkDays == null || muscDays == null) {
            error = "걷기·근력 운동 일수를 선택해 주세요."; return@launchStep
        }
        // 현실 범위 가드(#298 A-2): 화면 인라인이 1차지만 제출 시점에도 최종 방어(백엔드 gt=0/le= 이전).
        if (h < HEIGHT_MIN_CM || h > HEIGHT_MAX_CM) {
            error = "키는 ${HEIGHT_MIN_CM.toInt()}~${HEIGHT_MAX_CM.toInt()}cm 사이로 입력해 주세요."; return@launchStep
        }
        if (w < WEIGHT_MIN_KG || w > WEIGHT_MAX_KG) {
            error = "몸무게는 ${WEIGHT_MIN_KG.toInt()}~${WEIGHT_MAX_KG.toInt()}kg 사이로 입력해 주세요."; return@launchStep
        }
        val body = HealthProfileRequest(
            birthDate = birth,
            sex = sex!!,
            heightCm = h,
            weightKg = w,
            walkDays = walkDays!!,
            muscDays = muscDays!!,
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

    /** S4 → 유효한 체력검사 값 제출 후 결과로. 스킵은 skipAssessment() 경로만 사용한다. */
    fun submitAssessment(chairStandSec: Double) = launchStep("체력검사 제출") {
        val body = PhysicalAssessmentRequest(
            chairStandSkipped = false,
            chairStand5TimeSec = chairStandSec,
            sessionId = sessionId,
        )
        api.createPhysicalAssessment(body)
        predictAndFinish()
    }

    /** 위험도 예측 → 완주. profileId 가 없으면(비정상) 예외로 에러 처리. 예측은 미리 생성해 두되(대시보드 캐시),
     *  결과화면 없이 완주 신호만 세운다(#299).
     *  #298 C: 만 65세 미만은 예측 대상이 아니라 서버가 422(sarcopenia_prediction_preparing)를 준다. 이 경우
     *  가입/온보딩은 정상 완료돼야 하므로 **예측 없이(result=null) 완주**로 넘긴다(예측은 대시보드에서 "준비 중" 안내). */
    private suspend fun predictAndFinish() {
        val pid = profileId ?: throw IllegalStateException("기본 정보가 없습니다. 처음부터 다시 진행해 주세요.")
        result = try {
            api.createRiskPrediction(RiskPredictionRequest(pid))
        } catch (e: HttpException) {
            // 422 를 무조건 '예측 준비 중'으로 삼키면 다른 검증성 422(프로필 불일치·비즈니스 검증)까지 '예측 없는
            //   완주'로 위장된다(리뷰 #313). 서버가 이 케이스에만 내려주는 안정 코드(sarcopenia_prediction_preparing)
            //   일 때만 예측 없이 완주로 넘기고, 그 외 422 는 재던져 에러 안내 + 재시도로 돌린다.
            if (e.code() == 422 && isSarcopeniaPreparing(e)) {
                Log.i(TAG, "예측 대상 아님(만 65세 미만 = sarcopenia_prediction_preparing) — 예측 없이 온보딩 완료(#298 C)")
                null
            } else {
                throw e
            }
        }
        finished = true
    }

    /**
     * 422 응답이 '예측 준비 중'(만 65세 미만 등)인지 — 서버가 이 케이스에만 detail.code 로 내려주는 안정 코드로 판별한다.
     *  본문 파싱 실패나 코드 부재면 false → 그 422 는 준비 중이 아니라 실제 오류로 취급(재던짐). 본문은 한 번만 읽는다.
     */
    private fun isSarcopeniaPreparing(e: HttpException): Boolean =
        runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            ?.contains("sarcopenia_prediction_preparing") == true

    fun dismissError() { error = null }

    /**
     * 입력값과 이미 저장된 서버 상태는 유지하고 화면 단계만 되돌린다.
     * WELCOME 에선 이전 단계가 없어 false 를 돌려주고 화면 호스트가 종료 확인을 담당한다(#299: 결과화면 제거로 RESULT 없음).
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
        // 상한은 올해(하드코딩 2025 제거, #298): 해가 바뀌어도 미래 연도만 막고 올해 출생은 허용.
        // 일자는 '해당 월의 실제 일수'로(2월/윤년·30/31일) — 1월 33일·2월 30일 등을 거른다.
        if (y !in 1900..todayYear || m !in 1..12 || d !in 1..daysInMonth(y, m)) return null
        // 미래 생일 거부(리뷰 #313): 연도 상한이 todayYear 라 올해 안이면 통과하므로, 올해라도 오늘 이후(월/일)면
        //   막는다 — 안 그러면 2026-12-31 같은 미래 날짜가 유효 처리돼 ageYears 가 음수가 된다.
        if (y == todayYear && (m > todayMonth || (m == todayMonth && d > todayDay))) return null
        return "%04d-%02d-%02d".format(y, m, d)
    }

    /** 해당 연·월의 일수(minSdk 24 라 java.time 미사용, desugaring 불필요). 그레고리력 윤년 규칙. */
    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        else -> 0 // 1..12 밖이면 0 → 어떤 일자도 유효 범위(1..0)에 안 들어와 거부된다.
    }

    /**
     * 생년월일 인라인 검증 문구(#298 A). 연·월·일이 **모두 입력됐는데** 유효하지 않을 때만 문구를 준다
     *  (입력 도중엔 null 로 조용). "다음"까지 미루지 않고 그 자리에서 안내하기 위한 파생 상태.
     */
    val birthDateError: String?
        get() {
            if (birthYear.isBlank() || birthMonth.isBlank() || birthDay.isBlank()) return null
            if (composeBirthDate() == null) return "생년월일을 정확히 입력해 주세요"
            // 최소 가입 연령 하한(리뷰 #313): 만 14세 미만은 개인정보보호법상 법정대리인 동의가 필요하므로 가입을
            //   막는다. #298 의 50-64 허용(예측만 준비 중)은 그대로 두고 하한만 추가한다.
            val age = ageYears() ?: return null
            return if (age < MIN_SIGNUP_AGE) "만 ${MIN_SIGNUP_AGE}세 이상만 가입할 수 있어요" else null
        }

    /**
     * 만 65세 미만 안내(#298 C). 생년월일이 유효하고 예측 대상 미만일 때만 노출 — **막지 않고** 희망적 톤으로 안내.
     *  가입/온보딩은 계속 진행할 수 있고, 예측만 "준비 중"임을 알린다.
     */
    val underAgeNotice: String?
        get() {
            if (composeBirthDate() == null) return null
            val age = ageYears() ?: return null
            // 만 14~64세에게만 노출한다(리뷰 #313). 14세 미만은 birthDateError 가 '가입 불가'를 안내하므로,
            //   여기서 '예측 제외 기능 자유롭게 이용' 문구까지 뜨면 서로 충돌한다 → 하한(MIN_SIGNUP_AGE) 미만은 제외.
            //
            // 나이대를 둘로 나눈다. 이전에는 두 구간에 같은 문구를 썼는데, "50~64세 예측도 준비 중"은
            //   만 50세 미만에게는 자기 이야기가 아니라 남의 계획으로 읽혀 그냥 지나친다. 그리고 이 구간은
            //   키·몸무게 '모름'(추정)도 쓸 수 없는데(추정표가 만 50세부터, canEstimate) 그 사실을 아래
            //   입력칸에 가서야 알게 된다 — 먼저 알려주면 '모름'을 눌렀다가 막히는 상황 자체가 안 생긴다.
            return when {
                age !in MIN_SIGNUP_AGE until MIN_SUPPORTED_AGE -> null
                age >= MIN_ESTIMATE_AGE ->
                    "지금은 만 65세 이상 어르신에게 근감소증 예측을 제공하고 있어요. 50~64세 예측도 준비 중이니, " +
                        "그전까지는 예측을 제외한 기능을 자유롭게 이용하실 수 있어요."
                else ->
                    "지금은 만 65세 이상 어르신에게 근감소증 예측을 제공하고 있어요. 예측을 제외한 기능은 " +
                        "자유롭게 이용하실 수 있어요. 키·몸무게 추정은 만 50세 이상부터라 직접 입력해 주세요."
            }
        }

    companion object {
        const val TAG = "Onboarding"

        /**
         * 근감소증 **예측** 대상 최소 연령(만). #298 이후로는 **가입 차단 게이트가 아니다** — 65세 미만도 가입·온보딩을
         * 완료할 수 있고, 예측만 "준비 중"으로 안내한다. 위험도 모델이 65세 이상 기준이라 이 값으로 예측 안내를 가른다(리뷰 #75-4).
         */
        const val MIN_SUPPORTED_AGE = 65

        /**
         * 최소 **가입** 연령(만, 리뷰 #313). 만 14세 미만은 개인정보보호법상 법정대리인 동의가 필요하므로 가입 자체를
         * 막는다(예측 대상 연령 [MIN_SUPPORTED_AGE] 와는 별개 — 14~64세는 가입 가능, 예측만 "준비 중"). 값은 제품 결정.
         */
        const val MIN_SIGNUP_AGE = 14

        /** '모름' 추정치를 제공하는 최소 연령(만). 50~64 추정표 확장(#298 C)에 맞춰 65 → 50 으로 낮춘다. */
        const val MIN_ESTIMATE_AGE = 50

        // 키·몸무게 현실 범위(#298 A-2). 인라인 1차 검증값. 서버 DTO le= 는 더 넓은 근본 방어(별도 PR).
        const val HEIGHT_MIN_CM = 90.0
        const val HEIGHT_MAX_CM = 220.0
        const val WEIGHT_MIN_KG = 20.0
        const val WEIGHT_MAX_KG = 200.0
    }
}

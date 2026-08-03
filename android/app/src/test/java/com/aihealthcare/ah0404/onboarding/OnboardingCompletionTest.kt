package com.aihealthcare.ah0404.onboarding

import com.aihealthcare.ah0404.network.AgreementsRequest
import com.aihealthcare.ah0404.network.AgreementsResponse
import com.aihealthcare.ah0404.network.HealthProfileRequest
import com.aihealthcare.ah0404.network.HealthProfileResponse
import com.aihealthcare.ah0404.network.OnboardingApi
import com.aihealthcare.ah0404.network.PhysicalAssessmentRequest
import com.aihealthcare.ah0404.network.RiskPredictionRequest
import com.aihealthcare.ah0404.network.RiskPredictionResponse
import com.aihealthcare.ah0404.network.SessionCreateRequest
import com.aihealthcare.ah0404.network.SessionResponse
import com.aihealthcare.ah0404.network.SkipResponse
import com.aihealthcare.ah0404.network.SocialLoginRequest
import com.aihealthcare.ah0404.network.Term
import com.aihealthcare.ah0404.network.TermsListResponse
import com.aihealthcare.ah0404.network.TokenHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * 온보딩 완주(#299) — 결과화면(RESULT) 제거 후, 체력검사 제출/스킵 → 예측 생성이 끝나면 별도 결과화면 없이
 *  완주 신호(finished)만 세워 화면 호스트가 곧장 홈으로 보낸다. 완주해도 step 은 RESULT 로 가지 않는다(스텝 자체가 없음).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingCompletionTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** 완주까지 필요한 응답만 실제로 주는 happy-path fake. 예측 호출 여부를 기록한다. */
    private class FakeApi : OnboardingApi {
        var riskPredicted = false; private set

        override suspend fun guestLogin() = TODO()
        override suspend fun loginGoogle(body: SocialLoginRequest) = TODO()
        override suspend fun loginKakao(body: SocialLoginRequest) = TODO()
        override suspend fun getTerms() = TermsListResponse(
            listOf(
                Term("service", "1", isRequired = true),
                Term("privacy", "1", isRequired = true),
                Term("sensitive_health", "1", isRequired = true),
            ),
        )
        override suspend fun agreeTerms(body: AgreementsRequest) = AgreementsResponse("terms_agreed")
        override suspend fun createSession(body: SessionCreateRequest) =
            SessionResponse(sessionId = 1, status = "started")
        override suspend fun skipHealthCheck(sessionId: Int) =
            SkipResponse(sessionId = sessionId, status = "skipped", onboardingStatus = "completed")
        override suspend fun createHealthProfile(body: HealthProfileRequest) =
            HealthProfileResponse(profileId = 1, bmi = 22.3, proteinChallengeAllowed = true)
        override suspend fun createPhysicalAssessment(body: PhysicalAssessmentRequest) = TODO()
        override suspend fun createRiskPrediction(body: RiskPredictionRequest): RiskPredictionResponse {
            riskPredicted = true
            return RiskPredictionResponse(
                predictionId = 1,
                careStage = "good",
                displayMessage = "좋아요",
                onboardingStatus = "completed",
            )
        }
        override suspend fun getHome() = TODO()
    }

    @Test
    fun completes_to_home_without_result_step() = runTest {
        val api = FakeApi()
        val vm = OnboardingViewModel(api, todayYear = 2026, todayMonth = 7, todayDay = 15)

        vm.continueAuthenticated(); advanceUntilIdle()
        assertEquals(OnbStep.TERMS, vm.step)

        vm.agreeAll(); vm.submitAgreements(); advanceUntilIdle()
        assertEquals(OnbStep.PROFILE, vm.step)

        // 만 68세(2026 기준) 유효 프로필
        vm.apply {
            sex = "male"; birthYear = "1958"; birthMonth = "3"; birthDay = "1"
            setHeight("168"); setWeight("63"); walkDays = 5; muscDays = 2
        }
        vm.submitProfile(); advanceUntilIdle()
        assertEquals(OnbStep.ASSESSMENT, vm.step)
        assertFalse("체력검사 전엔 완주 신호가 없다", vm.finished)

        vm.skipAssessment(); advanceUntilIdle()
        assertTrue("체력검사 스킵 → 예측까지 끝나면 완주 신호", vm.finished)
        assertTrue("예측은 미리 생성해 둔다(대시보드 캐시)", api.riskPredicted)
        assertEquals("완주해도 step 은 ASSESSMENT 그대로 — RESULT 스텝은 없다(#299)", OnbStep.ASSESSMENT, vm.step)
    }

    @Test
    fun restart_clears_stale_finished_signal() = runTest {
        // Activity 수명 VM 특성상 완주 신호(finished)가 남은 채 재진입할 수 있다 —
        //   start()/continueAuthenticated() 시작점에서 finished=false 를 깔아 stale 완주로 즉시 홈
        //   라우팅되는 경로를 원천 차단하는지 검증(리뷰 #311 nit).
        val api = FakeApi()
        val vm = OnboardingViewModel(api, todayYear = 2026, todayMonth = 7, todayDay = 15)

        // 1) 한 번 완주시켜 finished=true 로 만든다.
        vm.continueAuthenticated(); advanceUntilIdle()
        vm.agreeAll(); vm.submitAgreements(); advanceUntilIdle()
        vm.apply {
            sex = "male"; birthYear = "1958"; birthMonth = "3"; birthDay = "1"
            setHeight("168"); setWeight("63"); walkDays = 5; muscDays = 2
        }
        vm.submitProfile(); advanceUntilIdle()
        vm.skipAssessment(); advanceUntilIdle()
        assertTrue(vm.finished)

        // 2) resetToWelcome 를 거치지 않고 재로그인(같은 Activity 범위 VM) — 시작점에서 완주 신호가 걷힌다.
        vm.continueAuthenticated(); advanceUntilIdle()
        assertFalse("재시작은 stale finished 를 초기화한다(리뷰 #311)", vm.finished)
        assertEquals(OnbStep.TERMS, vm.step)
    }

    // ── 완주 신호는 일회성 이벤트다(#383) ─────────────────────────────────────────
    // 회귀 배경: 탈퇴 → 같은 소셜 계정 재로그인은 서버에서 **미완료 신규 계정**이 되는데, 이 경로는
    //   온보딩 화면 안이 아니라 LoginRequiredScreen 에서 로그인하므로 start()/continueAuthenticated()
    //   초기화(#311 방어)를 거치지 않는다. Activity 수명 VM 에 남은 finished=true 가 화면이 붙는 순간
    //   재발화해 약관·프로필을 건너뛰고 홈으로 직행했다.

    @Test
    fun finished_is_consumed_after_the_host_routes_home() = runTest {
        val api = FakeApi()
        val vm = completedOnboardingVm(api)
        assertTrue("완주 직후에는 신호가 서 있다", vm.finished)

        // 화면 호스트가 홈 라우팅을 처리한 뒤 신호를 소비한다.
        vm.consumeFinished()

        assertFalse("소비 후에는 신호가 남지 않는다 — 다음 진입에서 재발화 금지(#383)", vm.finished)
    }

    @Test
    fun consumed_finish_does_not_refire_for_a_new_unfinished_account() = runTest {
        // #383 시나리오: 완주(게스트) → 홈 → 탈퇴 → 같은 소셜 계정 재로그인(= 미완료 신규 계정).
        //   재로그인은 온보딩 VM 을 거치지 않으므로, 신호가 소비돼 있지 않으면 그대로 홈 직행한다.
        val api = FakeApi()
        val vm = completedOnboardingVm(api)
        vm.consumeFinished() // 첫 완주를 호스트가 처리·소비

        // 탈퇴 후 재로그인으로 온보딩 화면에 다시 진입한 상태 — VM 은 그대로 살아 있다.
        assertFalse("stale 완주 신호가 남아 있으면 안 된다", vm.finished)

        // 이 계정으로 온보딩을 이어가면 약관부터 시작한다(홈 직행 아님).
        vm.continueAuthenticated(); advanceUntilIdle()
        assertEquals(OnbStep.TERMS, vm.step)
        assertFalse(vm.finished)
    }

    // ── 실기기 QA(2026-08-03)에서 드러난 구멍: finished 소비가 가드의 발동 조건을 없앤다 ──
    //   완주 → 홈에서 consumeFinished 로 신호가 내려가고, 탈퇴 후 재로그인으로 토큰은 있으므로
    //   기존 두 조건(finished / 토큰 없음)이 모두 빗나가 리셋이 걸리지 않았다. step=ASSESSMENT 가 그대로
    //   남아 새 계정이 약관을 건너뛰고 체력검사부터 시작했고, 이전 사용자 입력(PII)과 죽은 sessionId 까지
    //   함께 남아 건너뛰기 요청이 실패해 사용자가 앞뒤로 못 가고 갇혔다.

    @Test
    fun progress_of_another_auth_subject_is_detected_after_finish_was_consumed() = runTest {
        // 인증 주체가 바뀌면 authRevision 이 오른다(SessionStore.applyLogin). 탈퇴 후 재로그인이 그 경우다.
        TokenHolder.token = "qa-token" // start() 가 FakeApi.guestLogin()(TODO) 을 타지 않게 — 테스트 순서 의존 제거
        var auth = 7
        val api = FakeApi()
        val vm = completedOnboardingVm(api) { auth }
        vm.consumeFinished() // 호스트가 홈 라우팅을 처리 — 여기서 finished 는 이미 false 다

        assertFalse("완주 신호로는 더 이상 걸러낼 수 없다", vm.finished)
        assertFalse("같은 주체가 이어가는 동안에는 stale 이 아니다", vm.isProgressFromAnotherAuth())

        auth = 8 // 탈퇴 → 같은 소셜 계정 재로그인 = 새 미완료 계정

        assertTrue(
            "주체가 바뀐 뒤 남은 진행은 stale 이다 — 가드가 resetToWelcome 로 되돌려야 한다",
            vm.isProgressFromAnotherAuth(),
        )
    }

    @Test
    fun logging_in_during_onboarding_does_not_count_as_another_subject() = runTest {
        // 게스트로 시작 → 도중에 소셜 로그인하면 authRevision 이 오르지만 이건 '이어가는 로그인'이다.
        //   continueAuthenticated 가 주인을 갱신하므로 방금 시작한 흐름이 WELCOME 으로 되돌아가면 안 된다.
        TokenHolder.token = "qa-token"
        var auth = 3
        val vm = OnboardingViewModel(FakeApi(), todayYear = 2026, todayMonth = 7, todayDay = 15, authKey = { auth })
        vm.start(); advanceUntilIdle()
        vm.agreeAll(); vm.submitAgreements(); advanceUntilIdle()
        assertEquals(OnbStep.PROFILE, vm.step)

        auth = 4 // 온보딩 도중 소셜 로그인 — applyLogin 이 authRevision 을 올린다
        vm.continueAuthenticated(); advanceUntilIdle()

        assertFalse(
            "온보딩 중 정상 로그인은 stale 이 아니다 — 진행 중인 흐름을 되돌리면 안 된다",
            vm.isProgressFromAnotherAuth(),
        )
    }

    @Test
    fun welcome_is_never_stale_even_when_the_subject_changed() = runTest {
        // 남은 진행이 없으면(WELCOME) 주체가 달라도 되돌릴 게 없다 — 불필요한 리셋 방지.
        var auth = 1
        val vm = OnboardingViewModel(FakeApi(), todayYear = 2026, todayMonth = 7, todayDay = 15, authKey = { auth })
        auth = 2

        assertEquals(OnbStep.WELCOME, vm.step)
        assertFalse(vm.isProgressFromAnotherAuth())
    }

    @Test
    fun reset_clears_the_progress_owner_so_the_next_start_reclaims_it() = runTest {
        // 리셋 후에는 주인이 비므로, 다음 시작점(start/continueAuthenticated)이 지금 주체로 다시 확정한다.
        TokenHolder.token = "qa-token"
        var auth = 10
        val api = FakeApi()
        val vm = completedOnboardingVm(api) { auth }
        auth = 11
        vm.resetToWelcome()

        vm.continueAuthenticated(); advanceUntilIdle()
        assertEquals(OnbStep.TERMS, vm.step)
        assertFalse("새 주체로 다시 시작했으므로 stale 이 아니다", vm.isProgressFromAnotherAuth())
    }

    @Test
    fun resetToWelcome_clears_finished_for_the_entry_guard() = runTest {
        // 화면 진입 가드(#383)가 stale 완주를 발견하면 resetToWelcome 로 되돌린다 —
        //   신호뿐 아니라 이전 사용자의 입력(PII)까지 함께 비워야 한 폰 다인 시연에서 새지 않는다.
        val api = FakeApi()
        val vm = completedOnboardingVm(api)
        assertTrue(vm.finished)

        vm.resetToWelcome()

        assertFalse("진입 가드의 리셋은 완주 신호를 내린다", vm.finished)
        assertEquals(OnbStep.WELCOME, vm.step)
        assertEquals("이전 사용자의 입력도 남지 않는다", "", vm.birthYear)
    }

    /** 온보딩을 끝까지 진행해 완주 신호(finished=true)가 선 VM 을 만든다. */
    private suspend fun TestScope.completedOnboardingVm(
        api: FakeApi,
        authKey: () -> Int = { 0 },
    ): OnboardingViewModel {
        val vm = OnboardingViewModel(api, todayYear = 2026, todayMonth = 7, todayDay = 15, authKey = authKey)
        vm.start(); advanceUntilIdle()
        vm.agreeAll(); vm.submitAgreements(); advanceUntilIdle()
        vm.apply {
            sex = "male"; birthYear = "1958"; birthMonth = "3"; birthDay = "1"
            setHeight("168"); setWeight("63"); walkDays = 5; muscDays = 2
        }
        vm.submitProfile(); advanceUntilIdle()
        vm.skipAssessment(); advanceUntilIdle()
        return vm
    }

    // ── 예측 422 처리 범위(리뷰 #313): 준비 중 코드만 완주로, 그 외 422 는 실제 오류로 ──
    private fun http422(body: String) =
        HttpException(Response.error<Any>(422, body.toResponseBody("application/json".toMediaType())))

    @Test
    fun preparing_422_completes_onboarding_without_prediction() = runTest {
        // 서버가 code=sarcopenia_prediction_preparing 로 준 422 는 '예측 없는 정상 완주'로 넘긴다(#298 C).
        val api = object : OnboardingApi by FakeApi() {
            override suspend fun createRiskPrediction(body: RiskPredictionRequest): RiskPredictionResponse =
                throw http422("""{"detail":{"code":"sarcopenia_prediction_preparing","message":"준비 중"}}""")
        }
        val vm = OnboardingViewModel(api, todayYear = 2026, todayMonth = 7, todayDay = 15)
        vm.continueAuthenticated(); advanceUntilIdle()
        vm.agreeAll(); vm.submitAgreements(); advanceUntilIdle()
        vm.apply {
            sex = "male"; birthYear = "1970"; birthMonth = "1"; birthDay = "1" // 56세(≥14, <65)
            setHeight("168"); setWeight("63"); walkDays = 5; muscDays = 2
        }
        vm.submitProfile(); advanceUntilIdle()
        vm.skipAssessment(); advanceUntilIdle()
        assertTrue("준비 중 422 는 예측 없이 완주", vm.finished)
        assertNull("예측 결과는 없다(준비 중)", vm.result)
        assertNull("에러가 아니다", vm.error)
    }

    @Test
    fun non_preparing_422_surfaces_error_and_does_not_complete() = runTest {
        // 준비 중 코드가 아닌 422(검증성 오류 등)는 '예측 없는 완주'로 위장하지 않고 에러+재시도로 돌린다(리뷰 #313).
        val api = object : OnboardingApi by FakeApi() {
            override suspend fun createRiskPrediction(body: RiskPredictionRequest): RiskPredictionResponse =
                throw http422("""{"detail":"프로필 값이 올바르지 않습니다"}""")
        }
        val vm = OnboardingViewModel(api, todayYear = 2026, todayMonth = 7, todayDay = 15)
        vm.continueAuthenticated(); advanceUntilIdle()
        vm.agreeAll(); vm.submitAgreements(); advanceUntilIdle()
        vm.apply {
            sex = "male"; birthYear = "1970"; birthMonth = "1"; birthDay = "1"
            setHeight("168"); setWeight("63"); walkDays = 5; muscDays = 2
        }
        vm.submitProfile(); advanceUntilIdle()
        vm.skipAssessment(); advanceUntilIdle()
        assertFalse("다른 422 는 완주로 위장되지 않는다", vm.finished)
        assertTrue("에러로 재시도를 유도한다", vm.error != null)
    }
}

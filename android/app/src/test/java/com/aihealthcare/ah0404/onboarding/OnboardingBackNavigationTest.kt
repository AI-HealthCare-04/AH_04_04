package com.aihealthcare.ah0404.onboarding

import com.aihealthcare.ah0404.network.AgreementsRequest
import com.aihealthcare.ah0404.network.AgreementsResponse
import com.aihealthcare.ah0404.network.HealthProfileRequest
import com.aihealthcare.ah0404.network.OnboardingApi
import com.aihealthcare.ah0404.network.PhysicalAssessmentRequest
import com.aihealthcare.ah0404.network.RiskPredictionRequest
import com.aihealthcare.ah0404.network.SessionCreateRequest
import com.aihealthcare.ah0404.network.SessionResponse
import com.aihealthcare.ah0404.network.SocialLoginRequest
import com.aihealthcare.ah0404.network.Term
import com.aihealthcare.ah0404.network.TermsListResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingBackNavigationTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun onboarding_steps_have_expected_previous_destination() {
        assertEquals(OnbStep.WELCOME, previousOnboardingStep(OnbStep.TERMS))
        assertEquals(OnbStep.TERMS, previousOnboardingStep(OnbStep.PROFILE))
        assertEquals(OnbStep.PROFILE, previousOnboardingStep(OnbStep.ASSESSMENT))
    }

    @Test
    fun first_step_does_not_navigate_back() {
        // 결과화면(RESULT) 제거(#299) 후엔 WELCOME 만 이전 단계가 없다. 완주는 step 이 아니라 finished 로 알린다.
        assertNull(previousOnboardingStep(OnbStep.WELCOME))
    }

    // ── 기본 정보 → 약관 되돌아가기(#399) ────────────────────────────────────────
    // 화면에는 `subStep > 0` 일 때만 이전 버튼이 있어 기본 정보 첫 페이지에서 약관으로 갈 수 없었다.
    //   단계 이동 자체는 준비돼 있었으므로(위 매핑), 여기서는 되돌아간 뒤 **입력과 서버 상태가 유지되는지**를
    //   고정한다 — 유지되지 않으면 버튼을 열어주는 것이 오히려 손해다.

    @Test
    fun going_back_to_terms_keeps_entered_values_and_the_server_session() = runTest {
        val vm = OnboardingViewModel(FakeApi())
        vm.continueAuthenticated(); advanceUntilIdle()
        vm.agreeAll(); vm.submitAgreements(); advanceUntilIdle()
        assertEquals(OnbStep.PROFILE, vm.step)
        vm.apply {
            sex = "male"; birthYear = "1958"; birthMonth = "3"; birthDay = "1"
            setHeight("168"); setWeight("63")
        }

        assertTrue("기본 정보 첫 페이지에서도 약관으로 돌아갈 수 있다", vm.goBack())

        assertEquals(OnbStep.TERMS, vm.step)
        assertEquals("되돌아가도 입력은 남는다", "1958", vm.birthYear)
        assertEquals("168", vm.heightInput)
        assertTrue("이미 한 동의도 유지된다 — 다시 체크하게 만들지 않는다", vm.allRequiredAgreed)

        // 다시 앞으로 진행해도 세션을 새로 만들지 않는다(submitAgreements 는 sessionId 가 null 일 때만 생성).
        vm.submitAgreements(); advanceUntilIdle()
        assertEquals(OnbStep.PROFILE, vm.step)
        assertEquals("1958", vm.birthYear)
    }

    /** 약관·세션까지만 필요한 fake. */
    private class FakeApi : OnboardingApi {
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
        override suspend fun skipHealthCheck(sessionId: Int) = TODO()
        override suspend fun createHealthProfile(body: HealthProfileRequest) = TODO()
        override suspend fun createPhysicalAssessment(body: PhysicalAssessmentRequest) = TODO()
        override suspend fun createRiskPrediction(body: RiskPredictionRequest) = TODO()
        override suspend fun getHome() = TODO()
    }
}

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
}

package com.aihealthcare.ah0404.onboarding

import com.aihealthcare.ah0404.network.AgreementsRequest
import com.aihealthcare.ah0404.network.CohortDistributionResponse
import com.aihealthcare.ah0404.network.HealthProfileRequest
import com.aihealthcare.ah0404.network.HealthProfileResponse
import com.aihealthcare.ah0404.network.OnboardingApi
import com.aihealthcare.ah0404.network.PhysicalAssessmentRequest
import com.aihealthcare.ah0404.network.RiskPredictionRequest
import com.aihealthcare.ah0404.network.RiskPredictionResponse
import com.aihealthcare.ah0404.network.SessionCreateRequest
import com.aihealthcare.ah0404.network.SocialLoginRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 또래 분포 조회(#193)가 온보딩 완료를 막지 않는지 검증(리뷰 #302 블로커1).
 *  결과(createRiskPrediction)가 오면 즉시 RESULT 로 이동하고, 코호트 조회는 별도 코루틴이라 느려도(지연·타임아웃)
 *  결과 화면 진입을 지연시키지 않는다. 도착하면 그때 차트 데이터(cohort)가 채워진다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingCohortLoadTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeApi(private val cohortDelayMs: Long) : OnboardingApi {
        override suspend fun guestLogin() = TODO()
        override suspend fun loginGoogle(body: SocialLoginRequest) = TODO()
        override suspend fun loginKakao(body: SocialLoginRequest) = TODO()
        override suspend fun getTerms() = TODO()
        override suspend fun agreeTerms(body: AgreementsRequest) = TODO()
        override suspend fun createSession(body: SessionCreateRequest) = TODO()
        override suspend fun skipHealthCheck(sessionId: Int) = TODO() // sessionId=null 이라 호출 안 됨
        override suspend fun createHealthProfile(body: HealthProfileRequest) =
            HealthProfileResponse(profileId = 1, bmi = 23.5, proteinChallengeAllowed = true)
        override suspend fun createPhysicalAssessment(body: PhysicalAssessmentRequest) = TODO()
        override suspend fun createRiskPrediction(body: RiskPredictionRequest) =
            RiskPredictionResponse(
                predictionId = 1,
                careStage = "maintain",
                displayMessage = "결과",
                onboardingStatus = "completed",
            )
        override suspend fun getCohortDistribution(): CohortDistributionResponse {
            if (cohortDelayMs > 0) delay(cohortDelayMs) // 느린 코호트 조회(연결 지연·타임아웃) 재현
            return SAMPLE_COHORT
        }
        override suspend fun getHome() = TODO()
    }

    private fun readyVm(cohortDelayMs: Long) =
        OnboardingViewModel(FakeApi(cohortDelayMs), todayYear = 2026, todayMonth = 7, todayDay = 15).apply {
            sex = "male"; birthYear = "1955"; birthMonth = "3"; birthDay = "1" // 만 71세(65+)
            setHeight("170"); setWeight("68"); walkDays = 3; muscDays = 1
        }

    @Test
    fun `느린 코호트 조회가 결과 화면 진입을 막지 않는다`() = runTest(dispatcher) {
        val vm = readyVm(cohortDelayMs = 10_000)
        vm.submitProfile(); advanceUntilIdle()
        assertEquals(OnbStep.ASSESSMENT, vm.step)

        vm.skipAssessment(); runCurrent() // 코호트 delay 이전까지만 진행
        assertEquals("코호트 조회를 기다리지 않고 즉시 RESULT", OnbStep.RESULT, vm.step)
        assertNull("느린 코호트는 아직 미도착 — 차트만 나중에 뜬다", vm.cohort)

        advanceUntilIdle() // 코호트 delay 소진
        assertNotNull("도착하면 채워진다", vm.cohort)
        assertEquals(72, vm.cohort?.lowerCount)
    }

    @Test
    fun `코호트 조회 실패해도 결과는 정상 진입하고 차트만 미표시`() = runTest(dispatcher) {
        // 딜레이 없이 즉시 실패시키는 API
        val failing = object : OnboardingApi by FakeApi(0) {
            override suspend fun getCohortDistribution(): CohortDistributionResponse =
                throw RuntimeException("서버 미지원")
        }
        val vm = OnboardingViewModel(failing, todayYear = 2026, todayMonth = 7, todayDay = 15).apply {
            sex = "male"; birthYear = "1955"; birthMonth = "3"; birthDay = "1"
            setHeight("170"); setWeight("68"); walkDays = 3; muscDays = 1
        }
        vm.submitProfile(); advanceUntilIdle()
        vm.skipAssessment(); advanceUntilIdle()

        assertEquals(OnbStep.RESULT, vm.step)
        assertNull("실패는 차트만 미표시로 흡수", vm.cohort)
    }

    private companion object {
        val SAMPLE_COHORT = CohortDistributionResponse(
            probability = 0.18f,
            sex = "male",
            ageLabel = "69–75세",
            n = 600,
            lowerCount = 72,
        )
    }
}

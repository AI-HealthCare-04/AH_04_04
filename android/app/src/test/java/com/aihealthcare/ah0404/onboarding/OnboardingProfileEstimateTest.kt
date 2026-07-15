package com.aihealthcare.ah0404.onboarding

import com.aihealthcare.ah0404.network.AgreementsRequest
import com.aihealthcare.ah0404.network.HealthProfileRequest
import com.aihealthcare.ah0404.network.OnboardingApi
import com.aihealthcare.ah0404.network.PhysicalAssessmentRequest
import com.aihealthcare.ah0404.network.RiskPredictionRequest
import com.aihealthcare.ah0404.network.SessionCreateRequest
import com.aihealthcare.ah0404.network.SocialLoginRequest
import com.aihealthcare.ah0404.network.VoiceParseRequest
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 온보딩 키·몸무게 '모름' 추정치 로직 테스트 (백엔드 무변경, 앱 추정치 방식 A).
 *  - estimateBody: 성별·연령대 추정표 정확성.
 *  - VM: '모름' → 추정치 채움 + has_estimated_value, 수동 입력 → 플래그 해제.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingProfileEstimateTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // 추정 로직만 검증하므로 api 는 호출되지 않는다(모든 메서드 미구현 fake).
    private class FakeApi : OnboardingApi {
        override suspend fun guestLogin() = TODO()
        override suspend fun loginGoogle(body: SocialLoginRequest) = TODO()
        override suspend fun loginKakao(body: SocialLoginRequest) = TODO()
        override suspend fun getTerms() = TODO()
        override suspend fun agreeTerms(body: AgreementsRequest) = TODO()
        override suspend fun createSession(body: SessionCreateRequest) = TODO()
        override suspend fun parseSessionVoice(sessionId: Int, body: VoiceParseRequest) = TODO()
        override suspend fun skipHealthCheck(sessionId: Int) = TODO()
        override suspend fun createHealthProfile(body: HealthProfileRequest) = TODO()
        override suspend fun createPhysicalAssessment(body: PhysicalAssessmentRequest) = TODO()
        override suspend fun createRiskPrediction(body: RiskPredictionRequest) = TODO()
        override suspend fun getHome() = TODO()
    }

    private fun vm(year: Int = 2026) = OnboardingViewModel(FakeApi(), currentYear = year)

    // ── estimateBody 표 ──────────────────────────────────────────────
    @Test fun estimate_male_65_74() = assertEquals(166 to 65, estimateBody("male", 68))
    @Test fun estimate_male_75plus() = assertEquals(163 to 62, estimateBody("male", 80))
    @Test fun estimate_female_65_74() = assertEquals(153 to 56, estimateBody("female", 68))
    @Test fun estimate_female_75plus() = assertEquals(150 to 53, estimateBody("female", 82))
    @Test fun estimate_null_sex_falls_back_to_male() = assertEquals(166 to 65, estimateBody(null, 68))
    @Test fun estimate_null_age_uses_65_74_band() = assertEquals(166 to 65, estimateBody("male", null))

    // ── VM 플래그/전송값 ─────────────────────────────────────────────
    @Test
    fun height_unknown_fills_estimate_and_sets_flag() {
        val vm = vm(2026).apply { sex = "female"; birthYear = "1958" } // 2026-1958=68 → 65-74
        vm.markHeightUnknown()
        assertEquals("153", vm.heightCm)
        assertTrue(vm.heightEstimated)
        assertTrue(vm.hasEstimatedValue)
    }

    @Test
    fun weight_unknown_uses_age_band() {
        val vm = vm(2026).apply { sex = "male"; birthYear = "1945" } // 81 → 75+
        vm.markWeightUnknown()
        assertEquals("62", vm.weightKg)
        assertTrue(vm.weightEstimated)
    }

    @Test
    fun manual_input_clears_estimated_flag() {
        val vm = vm(2026).apply { sex = "male"; birthYear = "1958" }
        vm.markHeightUnknown()
        assertTrue(vm.heightEstimated)
        vm.setHeight("170") // 사용자가 직접 입력 → 실제값
        assertFalse(vm.heightEstimated)
        assertFalse(vm.hasEstimatedValue) // 몸무게도 추정 아님
    }

    @Test
    fun both_manual_means_no_estimate() {
        val vm = vm(2026)
        vm.setHeight("172"); vm.setWeight("70")
        assertFalse(vm.hasEstimatedValue)
    }

    @Test
    fun waist_unknown_clears_field() {
        val vm = vm(2026).apply { waistCm = "88" }
        vm.markWaistUnknown()
        assertEquals("", vm.waistCm)
    }

    /** 재란 #75 nit: 수동 "0"/음수 키·몸무게는 백엔드 gt=0 전에 앱에서 막고 단계 진행 안 함. */
    @Test
    fun submit_rejects_nonpositive_height() = runTest {
        val vm = vm(2026).apply {
            birthYear = "1958"; birthMonth = "3"; birthDay = "1"
            sex = "male"; walkingPractice = true; strengthExercise = false
            setWeight("60"); setHeight("0")
        }
        vm.submitProfile(); advanceUntilIdle()
        // 양수 가드 메시지가 떠야 하고(=API 경로로 안 넘어감), 다음 단계로 진행하지 않는다.
        assertTrue(vm.error?.contains("0보다 큰") == true)
        assertFalse(vm.step == OnbStep.ASSESSMENT)
    }
}

package com.aihealthcare.ah0404.onboarding

import com.aihealthcare.ah0404.network.AgreementsRequest
import com.aihealthcare.ah0404.network.AgreementsResponse
import com.aihealthcare.ah0404.network.AuthResponse
import com.aihealthcare.ah0404.network.HealthProfileRequest
import com.aihealthcare.ah0404.network.OnbUser
import com.aihealthcare.ah0404.network.OnboardingApi
import com.aihealthcare.ah0404.network.PhysicalAssessmentRequest
import com.aihealthcare.ah0404.network.RiskPredictionRequest
import com.aihealthcare.ah0404.network.SessionCreateRequest
import com.aihealthcare.ah0404.network.SessionResponse
import com.aihealthcare.ah0404.network.SocialLoginRequest
import com.aihealthcare.ah0404.network.Term
import com.aihealthcare.ah0404.network.TermsListResponse
import com.aihealthcare.ah0404.network.TokenHolder
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
 * '체험으로 시작하기'가 살아 있는 소셜 세션을 게스트로 덮어쓰던 문제(#398).
 *
 * `start()` 는 토큰이 있으면 게스트 로그인만 건너뛰고 `isGuest = true` 는 그대로 세웠다. 소셜 토큰을 든 채
 * 시작 화면에 서는 경우가 실제로 있어서(탈퇴 후 재로그인 → 진입 가드가 WELCOME 으로 되돌림, #383),
 * 소셜 계정이 게스트로 취급돼 완주해도 영속화되지 않았다 — 다음 실행에서 다시 로그인해야 했다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingGuestStartTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        Dispatchers.resetMain()
        TokenHolder.token = ""
    }

    /** 약관까지만 필요한 fake. 게스트 로그인이 실제로 불렸는지 기록한다. */
    private class FakeApi : OnboardingApi {
        var guestLoginCalled = false; private set

        override suspend fun guestLogin(): AuthResponse {
            guestLoginCalled = true
            return AuthResponse(user = guestUser(), accessToken = "guest-token")
        }
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

        private fun guestUser() =
            OnbUser(userId = 1, nickname = "체험", onboardingStatus = "pending", isGuest = true)
    }

    @Test
    fun start_does_not_mark_guest_when_a_social_session_is_active() = runTest {
        // 탈퇴 후 재로그인 → 진입 가드가 WELCOME 으로 되돌린 상태. 토큰은 **소셜**이다.
        TokenHolder.token = "social-token"
        val api = FakeApi()
        val vm = OnboardingViewModel(api, socialAuth = { true })

        vm.start(); advanceUntilIdle()

        assertFalse("소셜 세션 위에서는 게스트로 표시하지 않는다 — 완주해도 영속화 안 되던 원인", vm.isGuest)
        assertEquals("인증된 흐름을 이어가 약관부터 진행한다", OnbStep.TERMS, vm.step)
        assertFalse("소셜 토큰이 있으므로 게스트 로그인은 부르지 않는다", api.guestLoginCalled)
        assertEquals("소셜 토큰을 덮어쓰지 않는다", "social-token", TokenHolder.token)
    }

    @Test
    fun start_still_begins_a_guest_flow_without_a_social_session() = runTest {
        // 평범한 '체험으로 시작하기' — 소셜 세션이 없으면 기존 동작 그대로.
        TokenHolder.token = ""
        val api = FakeApi()
        val vm = OnboardingViewModel(api, socialAuth = { false })

        vm.start(); advanceUntilIdle()

        assertTrue("게스트 온보딩은 그대로 동작한다", vm.isGuest)
        assertTrue("토큰이 없으면 게스트 로그인을 수행한다", api.guestLoginCalled)
        assertEquals(OnbStep.TERMS, vm.step)
    }

    @Test
    fun a_leftover_guest_token_still_counts_as_a_guest_start() = runTest {
        // 같은 세션에서 이미 게스트 로그인을 한 뒤 되돌아온 경우 — 토큰이 있어도 소셜이 아니므로 게스트가 맞다.
        TokenHolder.token = "guest-token"
        val api = FakeApi()
        val vm = OnboardingViewModel(api, socialAuth = { false })

        vm.start(); advanceUntilIdle()

        assertTrue(vm.isGuest)
        assertFalse("토큰이 이미 있으면 게스트 로그인을 다시 부르지 않는다", api.guestLoginCalled)
    }
}

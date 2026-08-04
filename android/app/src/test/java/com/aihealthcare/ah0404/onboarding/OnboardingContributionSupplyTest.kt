package com.aihealthcare.ah0404.onboarding

import android.content.Context
import com.aihealthcare.ah0404.network.AgreementsRequest
import com.aihealthcare.ah0404.network.AgreementsResponse
import com.aihealthcare.ah0404.network.AuthSession
import com.aihealthcare.ah0404.network.ContributionItemDto
import com.aihealthcare.ah0404.network.HealthProfileRequest
import com.aihealthcare.ah0404.network.HealthProfileResponse
import com.aihealthcare.ah0404.network.OnboardingApi
import com.aihealthcare.ah0404.network.PhysicalAssessmentRequest
import com.aihealthcare.ah0404.network.RiskPredictionRequest
import com.aihealthcare.ah0404.network.RiskPredictionResponse
import com.aihealthcare.ah0404.network.SessionCreateRequest
import com.aihealthcare.ah0404.network.SessionResponse
import com.aihealthcare.ah0404.network.SessionStore
import com.aihealthcare.ah0404.network.SkipResponse
import com.aihealthcare.ah0404.network.SocialLoginRequest
import com.aihealthcare.ah0404.network.Term
import com.aihealthcare.ah0404.network.TermsListResponse
import com.aihealthcare.ah0404.network.TokenCipher
import com.aihealthcare.ah0404.network.TokenEnvelope
import com.aihealthcare.ah0404.record.SharedPrefsContributionCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 온보딩 예측이 기여도(#406)의 **최초 공급 경로**임을 고정한다 — 리뷰 P1.
 *
 *  서버는 기여도를 저장하지 않으므로(#411) 온보딩 create 응답에서 놓치면, 다음 재계산(하루 1회 정책 #388)
 *  전까지 기록 탭 기여도 카드가 아예 뜨지 않는다.
 *
 *  ⚠️ 그리고 **저장 시점**이 함정이다. 캐시는 계정 스코프(`SessionStore.persistentUserId`)인데 그 값은
 *  완주 확정([SessionStore.markOnboarded])에서 세워진다 — 예측 응답을 받은 자리에서 곧바로 저장하면
 *  아직 null 이라 조용히 무시된다. 아래 두 테스트가 그 순서를 고정한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingContributionSupplyTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    /**
     * 기여도 캐시는 Keystore AES/GCM 봉투로 저장되는데(#406 리뷰 P1) Keystore 는 JVM·Robolectric 에
     * 없다. 주입하지 않으면 암호화가 실패해 **저장이 조용히 생략**되고, 이 테스트가 검증하려는
     * "최초 공급이 남는다"가 항상 거짓이 된다([ContributionCacheTest] 와 같은 방식의 가역 fake).
     */
    private class FakeCipher : TokenCipher {
        override fun encrypt(plain: String): String = TokenEnvelope.build("iv", "fk." + plain.reversed())

        override fun decrypt(stored: String): String? {
            val (_, cipher) = TokenEnvelope.parse(stored) ?: return null
            if (!cipher.startsWith("fk.")) return null
            return cipher.removePrefix("fk.").reversed()
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        SessionStore.clearAuthentication(context)
        SharedPrefsContributionCache.clearAll(context)
    }

    private val supplied = listOf(
        ContributionItemDto(feature = "waist_cm", effectOnScoreLogOdds = -0.83),
        ContributionItemDto(feature = "musc_days", effectOnScoreLogOdds = 0.41),
    )

    /** 완주까지 필요한 응답만 주는 happy-path fake. 예측 응답에 기여도를 싣는다(#411 계약). */
    private inner class FakeApi : OnboardingApi {
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
        override suspend fun createRiskPrediction(body: RiskPredictionRequest) = RiskPredictionResponse(
            predictionId = 42,
            careStage = "good",
            displayMessage = "좋아요",
            onboardingStatus = "completed",
            contributions = supplied,
        )
        override suspend fun getHome() = TODO()
    }

    /** 미완료 소셜 로그인(약관 전) — currentUserId 는 잡히지만 persistentUserId 는 아직 null 이다. */
    private fun loginIncompleteSocial(userId: Int) = SessionStore.applyLogin(
        context,
        AuthSession(accessToken = "t", isGuest = false, onboardingCompleted = false, userId = userId),
    )

    private fun runOnboardingToFinish(vm: OnboardingViewModel) {
        vm.continueAuthenticated()
    }

    @Test
    fun `온보딩 예측 기여도는 완주 확정 뒤에 저장된다`() = runTest(dispatcher) {
        loginIncompleteSocial(11)
        val cache = SharedPrefsContributionCache(context, FakeCipher())
        val vm = OnboardingViewModel(
            FakeApi(),
            todayYear = 2026,
            todayMonth = 7,
            todayDay = 15,
            contributionCache = cache,
        )

        runOnboardingToFinish(vm); advanceUntilIdle()
        vm.agreeAll(); vm.submitAgreements(); advanceUntilIdle()
        vm.apply {
            sex = "male"; birthYear = "1958"; birthMonth = "3"; birthDay = "1"
            setHeight("168"); setWeight("63"); walkDays = 5; muscDays = 2
        }
        vm.submitProfile(); advanceUntilIdle()
        vm.skipAssessment(); advanceUntilIdle()
        assertTrue("예측까지 끝나 완주 신호가 섰다", vm.finished)

        // 이 시점엔 아직 계정 스코프가 없다 — 여기서 저장했다면 조용히 사라졌을 것이다.
        assertTrue("완주 확정 전에는 저장 대상이 아니다", cache.load(42).isEmpty())

        // 화면 호스트(MainActivity)의 실제 순서: markOnboarded → persistScoreContributions.
        SessionStore.markOnboarded(context, isGuest = false)
        vm.persistScoreContributions()

        assertEquals(
            "온보딩 예측의 기여도가 최초 공급으로 남는다",
            listOf("waist_cm", "musc_days"),
            SharedPrefsContributionCache(context, FakeCipher()).load(42).map { it.feature },
        )
    }

    @Test
    fun `게스트는 완주해도 기여도를 기기에 남기지 않는다`() = runTest(dispatcher) {
        // 게스트 토큰은 재시작 시 소실된다 — 그 기여도를 다음 사용자에게 붙이면 안 된다(#153 방침).
        val cache = SharedPrefsContributionCache(context, FakeCipher())
        val vm = OnboardingViewModel(
            FakeApi(),
            todayYear = 2026,
            todayMonth = 7,
            todayDay = 15,
            contributionCache = cache,
        )

        runOnboardingToFinish(vm); advanceUntilIdle()
        vm.agreeAll(); vm.submitAgreements(); advanceUntilIdle()
        vm.apply {
            sex = "male"; birthYear = "1958"; birthMonth = "3"; birthDay = "1"
            setHeight("168"); setWeight("63"); walkDays = 5; muscDays = 2
        }
        vm.submitProfile(); advanceUntilIdle()
        vm.skipAssessment(); advanceUntilIdle()

        SessionStore.markOnboarded(context, isGuest = true)
        vm.persistScoreContributions()

        assertTrue("게스트는 persistentUserId 가 null → 저장 없음", cache.load(42).isEmpty())
    }
}

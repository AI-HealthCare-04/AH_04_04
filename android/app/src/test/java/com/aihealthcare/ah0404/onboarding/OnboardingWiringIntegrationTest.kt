package com.aihealthcare.ah0404.onboarding

import com.aihealthcare.ah0404.network.Agreement
import com.aihealthcare.ah0404.network.AgreementsRequest
import com.aihealthcare.ah0404.network.HealthProfileRequest
import com.aihealthcare.ah0404.network.OnboardingApi
import com.aihealthcare.ah0404.network.PhysicalAssessmentRequest
import com.aihealthcare.ah0404.network.RiskPredictionRequest
import com.aihealthcare.ah0404.network.SessionCreateRequest
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit

/**
 * 온보딩 배선 proof — 실행 중인 dev 백엔드(정본)에 OnboardingApi 를 실제로 던져
 * Kotlin DTO 직렬화/역직렬화 + 계약이 end-to-end 로 도는지 검증한다.
 *
 * 대상: 기본 http://localhost:8010 (격리 dev 스모크 스택). `-Donboarding.baseUrl=...` 로 교체 가능.
 * append-only(매 실행 새 게스트) — 정리 불필요. 백엔드 미기동 시 assumeTrue 로 skip(실패 아님).
 * 실행: ./gradlew :app:testDebugUnitTest --tests "*OnboardingWiringIntegrationTest*"
 */
class OnboardingWiringIntegrationTest {

    private val base: String =
        System.getProperty("onboarding.baseUrl") ?: "http://localhost:8010/api/v1/"

    @Volatile
    private var token: String = ""

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val request = if (token.isNotEmpty()) {
                original.newBuilder().addHeader("Authorization", "Bearer $token").build()
            } else {
                original
            }
            chain.proceed(request)
        }
        .build()

    private val api: OnboardingApi = Retrofit.Builder()
        .baseUrl(base)
        .client(client)
        .addConverterFactory(
            Json { ignoreUnknownKeys = true }.asConverterFactory("application/json; charset=UTF-8".toMediaType())
        )
        .build()
        .create(OnboardingApi::class.java)

    private fun backendUp(): Boolean = try {
        val healthUrl = base.removeSuffix("api/v1/") + "health"
        client.newCall(Request.Builder().url(healthUrl).build()).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    @Test
    fun onboarding_happy_path_pending_to_completed() = runBlocking {
        assumeTrue("dev 백엔드($base) 미기동 → proof skip", backendUp())

        // 1) 게스트 → pending
        val auth = api.guestLogin()
        assertEquals("pending", auth.user.onboardingStatus)
        assertTrue("access_token 발급", auth.accessToken.isNotEmpty())
        token = auth.accessToken

        // 2) 약관(인증 필요) → 목록 존재
        val terms = api.getTerms()
        assertTrue("약관 목록 비어있지 않음", terms.terms.isNotEmpty())

        // 3) 약관 동의(필수 true, marketing false) → terms_agreed
        val agreements = AgreementsRequest(
            terms.terms.map { Agreement(it.termsType, it.version, agreed = it.termsType != "marketing") }
        )
        assertEquals("terms_agreed", api.agreeTerms(agreements).onboardingStatus)

        // 4) 세션 생성 → started, session_id
        val session = api.createSession(SessionCreateRequest("form"))
        assertEquals("started", session.status)

        // 5) 건강 프로필 → profile_id, bmi
        val profile = api.createHealthProfile(
            HealthProfileRequest(
                birthDate = "1958-03-21", sex = "male", heightCm = 168.0, weightKg = 63.5,
                walkingPractice = true, strengthExercise = false, waistCm = 84.0, sessionId = session.sessionId,
            )
        )
        assertTrue("profile_id 발급", profile.profileId > 0)
        assertTrue("bmi 계산됨", profile.bmi > 0)

        // 6) 기초체력검사 → activity_profile(난이도)
        val assessment = api.createPhysicalAssessment(
            PhysicalAssessmentRequest(
                sessionId = session.sessionId, chairStand5TimeSec = 12.4,
                walk6mTimeSec = 6.1, walk6mDistanceM = 6.0,
            )
        )
        assertNotNull("난이도 산정", assessment.activityProfile.currentLevel)

        // 7) 위험도 예측 → onboarding_status = completed (pending→completed 최종 전이)
        val risk = api.createRiskPrediction(RiskPredictionRequest(profile.profileId))
        assertEquals("completed", risk.onboardingStatus)
        assertTrue("display_message 존재", risk.displayMessage.isNotEmpty())

        // 8) 홈 → latest_prediction 노출(온보딩 성공 판정)
        val home = api.getHome()
        assertNotNull("home.latest_prediction 노출돼야 온보딩 성공", home.latestPrediction)
    }
}

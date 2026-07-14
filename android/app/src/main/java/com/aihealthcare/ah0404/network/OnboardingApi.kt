package com.aihealthcare.ah0404.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 온보딩 8단계 Retrofit 계약 — BACKEND_ONBOARDING_CONTRACT.md(dev 정본) 매핑.
 * 인증 필요 요청은 NetworkClient 의 OkHttp Interceptor 가 TokenHolder.token 을 자동 첨부한다.
 * 실패 응답은 전역 `{ "error_detail": "..." }` (422 스키마 / 400 비즈니스 / 401 인증).
 */
interface OnboardingApi {
    // 1) 진입/인증 (인증 불필요) — 게스트는 매 호출마다 새 익명 계정
    @POST("auth/guest")
    suspend fun guestLogin(): AuthResponse

    // 소셜(현재 OAuth 미설정이라 501) — 크리덴셜 준비 후 동작
    @POST("auth/login/google")
    suspend fun loginGoogle(@Body body: SocialLoginRequest): AuthResponse

    @POST("auth/login/kakao")
    suspend fun loginKakao(@Body body: SocialLoginRequest): AuthResponse

    // 2) 약관 목록 — ⚠️ 인증 필요(계약 정정 ⓐ). 게스트 로그인 직후라 토큰 보유.
    @GET("terms")
    suspend fun getTerms(): TermsListResponse

    // 3) 약관 동의 — 200 OK, → terms_agreed
    @POST("users/me/agreements")
    suspend fun agreeTerms(@Body body: AgreementsRequest): AgreementsResponse

    // 4) 건강체크 세션 생성 — session_id 를 5·6 에 넘기면 세션 연결
    @POST("health-check/sessions")
    suspend fun createSession(@Body body: SessionCreateRequest = SessionCreateRequest()): SessionResponse

    // 4b) (선택) 세션 스코프 음성 파싱 — VoiceApi 의 stateless /voice/parse 와 다른 엔드포인트
    @POST("health-check/sessions/{session_id}/voice")
    suspend fun parseSessionVoice(
        @Path("session_id") sessionId: Int,
        @Body body: VoiceParseRequest,
    ): VoiceParseResponse

    // 7-대체) 체력검사 스킵 → completed (기본 난이도)
    @POST("health-check/sessions/{session_id}/skip")
    suspend fun skipHealthCheck(@Path("session_id") sessionId: Int): SkipResponse

    // 5) 건강 프로필 저장 — profile_id 반환(→7). 세션 자동 완료.
    @POST("health-profiles")
    suspend fun createHealthProfile(@Body body: HealthProfileRequest): HealthProfileResponse

    // 6) 기초체력검사 — 난이도(activity_profile) 산정. 스킵 상호배제 규칙 준수 필요.
    @POST("physical-assessments")
    suspend fun createPhysicalAssessment(@Body body: PhysicalAssessmentRequest): PhysicalAssessmentResponse

    // 7) 위험도 예측 → onboarding_status = completed (온보딩 종료)
    @POST("risk-predictions")
    suspend fun createRiskPrediction(@Body body: RiskPredictionRequest): RiskPredictionResponse

    // 8) 홈 — latest_prediction 노출로 온보딩 성공 판정
    @GET("home")
    suspend fun getHome(): HomeResponse
}

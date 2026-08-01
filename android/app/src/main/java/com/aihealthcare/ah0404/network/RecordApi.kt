package com.aihealthcare.ah0404.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** `_13 나의 기록` 화면의 예측 추이와 활동 기록 API. */
interface RecordApi {
    /** 오래된 기록부터 최신 기록 순서로 연속 점수와 모델 비교 상태를 조회한다. */
    @GET("risk-predictions/me/history")
    suspend fun getRiskHistory(@Query("limit") limit: Int = 7): RiskHistoryResponse

    /** 단일일(date) 또는 기간(from~to, 포함) 조회. 기록 탭 달력·선그래프는 기간 조회를 쓴다(#272). */
    @GET("mission-logs")
    suspend fun getMissionLogs(
        @Query("date") date: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): MissionLogListResponse

    /** 예측 대시보드(#193) 개인화 입력 — 등록된 신체값 + 최근 7일 걷기/운동 요일 수. */
    @GET("dashboard/prediction-inputs")
    suspend fun getPredictionInputs(): PredictionInputsResponse

    /** 걷기 일별 걸음·분(#기록탭 §5.3). */
    @GET("dashboard/walking-daily")
    suspend fun getWalkingDaily(@Query("days") days: Int = 7): WalkingDailyResponse

    /** 챌린지 유형별 누적 완료 횟수(#기록탭 §5.4). */
    @GET("dashboard/challenge-totals")
    suspend fun getChallengeTotals(): ChallengeTotalsResponse

    /** 월별 스탬프(#기록탭 §5.2 달력). month="YYYY-MM". */
    @GET("dashboard/stamps")
    suspend fun getStamps(@Query("month") month: String): StampsResponse

    /** 근육 건강 점수 최신값(#기록탭 §3). 코호트표 미탑재·65세 미만이면 muscle_score=null. */
    @GET("risk-predictions/me/latest")
    suspend fun getLatestPrediction(): RiskLatestResponse

    /** what-if 점수 시뮬레이션(#기록탭 §4) — 걷기 0~7·근력 0~5 각 지점 점수. */
    @GET("dashboard/score-simulation")
    suspend fun getScoreSimulation(): ScoreSimulationResponse

    /**
     * 또래 분포 차트(#193) — 근육 건강 정보의 '또래 중 내 위치' 카드. 최신 예측 기준 코호트 분포.
     *  코호트표 미탑재·65세 미만이면 서버가 실패로 응답 → 차트만 미표시(다른 섹션 무영향).
     */
    @GET("risk-predictions/me/cohort-distribution")
    suspend fun getCohortDistribution(): CohortDistributionResponse

    /** 5STS 측정 이력(#353, 최신순·측정 기록만). 스킵만 있으면 빈 목록. */
    @GET("physical-assessments/me/history")
    suspend fun getStsHistory(@Query("limit") limit: Int = 20): StsHistoryResponse

    /**
     * 근육 건강 점수 재평가 — 최신 프로필 + 최근 활동으로 **새 예측을 생성**한다.
     *  '내 정보' 저장 후 이걸 불러야 편집이 점수에 실제 반영된다: GET latest 는 저장된 마지막
     *  예측을 돌려줄 뿐이라, 새 예측 없이는 프로필을 고쳐도 점수가 영원히 안 바뀐다(점수 모델
     *  배포 전 가입 계정은 "준비 중"에 머무는 문제 포함). 65세 미만이면 서버가 422 로 거른다.
     */
    @POST("risk-predictions/reassess")
    suspend fun reassessRiskPrediction(@Body body: RiskReassessRequest = RiskReassessRequest()): RiskReassessResponse

    /**
     * 예측 결과 체감 피드백(#357). 멱등 PUT — 예측당 1회는 서버 UNIQUE + 로컬 노출 기록이 보장한다.
     * 실패해도 화면 흐름을 막지 않는다(fire-and-forget, sts-overlay 이벤트와 동일 정책).
     */
    @PUT("risk-predictions/{predictionId}/feedback")
    suspend fun submitPredictionFeedback(
        @Path("predictionId") predictionId: Int,
        @Body body: PredictionFeedbackRequest,
    )
}

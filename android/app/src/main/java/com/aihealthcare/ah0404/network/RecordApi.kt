package com.aihealthcare.ah0404.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
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
     * 근육 건강 점수 재평가 — 최신 프로필 + 최근 활동으로 **새 예측을 생성**한다.
     *  '내 정보' 저장 후 이걸 불러야 편집이 점수에 실제 반영된다: GET latest 는 저장된 마지막
     *  예측을 돌려줄 뿐이라, 새 예측 없이는 프로필을 고쳐도 점수가 영원히 안 바뀐다(점수 모델
     *  배포 전 가입 계정은 "준비 중"에 머무는 문제 포함). 65세 미만이면 서버가 422 로 거른다.
     */
    @POST("risk-predictions/reassess")
    suspend fun reassessRiskPrediction(@Body body: RiskReassessRequest = RiskReassessRequest()): RiskReassessResponse
}

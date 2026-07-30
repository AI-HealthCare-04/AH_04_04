package com.aihealthcare.ah0404.network

import retrofit2.http.GET
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
}

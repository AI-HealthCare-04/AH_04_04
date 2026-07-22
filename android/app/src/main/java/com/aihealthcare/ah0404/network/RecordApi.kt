package com.aihealthcare.ah0404.network

import retrofit2.http.GET
import retrofit2.http.Query

/** `_13 나의 기록` 화면의 예측 추이와 활동 기록 API. */
interface RecordApi {
    /** 오래된 기록부터 최신 기록 순서로 연속 점수와 모델 비교 상태를 조회한다. */
    @GET("risk-predictions/me/history")
    suspend fun getRiskHistory(@Query("limit") limit: Int = 7): RiskHistoryResponse

    @GET("mission-logs")
    suspend fun getMissionLogs(@Query("date") date: String? = null): MissionLogListResponse
}

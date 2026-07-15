package com.aihealthcare.ah0404.network

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * `_13 나의 기록` 화면용 Retrofit 계약 — dev 백엔드 정본 매핑.
 *
 *  - GET /risk-predictions/me/history : care_stage 추이(#62). ⚠️ 비노출 계약(#57) —
 *      응답에 risk_level·risk_score 없음, care_stage(순화 등급)만.
 *  - GET /mission-logs               : 수행한 미션 로그 목록(활동 요약 산출용).
 *
 *  인증 토큰은 NetworkClient 의 OkHttp Interceptor 가 자동 첨부한다.
 */
interface RecordApi {
    // limit: 최근 N건(1..30, 기본 7). 최신순 정렬은 서버 계약을 따른다.
    @GET("risk-predictions/me/history")
    suspend fun getRiskHistory(@Query("limit") limit: Int = 7): RiskHistoryResponse

    // date 생략 시 전체 기간. `_13` 활동 요약은 전체 누적으로 표시한다.
    @GET("mission-logs")
    suspend fun getMissionLogs(@Query("date") date: String? = null): MissionLogListResponse
}

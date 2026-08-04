package com.aihealthcare.ah0404.network

import retrofit2.http.GET

/**
 * 운동 영상 Retrofit 계약 — dev 백엔드 정본(#72). 인증 토큰은 OkHttp Interceptor 가 자동 첨부.
 * 진입 시 1회 조회 후 VM 캐시. order 순 4단계.
 */
interface ExerciseVideoApi {
    @GET("exercise-videos")
    suspend fun getExerciseVideos(): ExerciseVideosResponse
}

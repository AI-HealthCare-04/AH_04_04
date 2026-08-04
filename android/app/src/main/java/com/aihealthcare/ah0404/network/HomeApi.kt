package com.aihealthcare.ah0404.network

import retrofit2.http.GET

/**
 * 홈(_3) Retrofit 계약 — dev 백엔드 정본(GET /home). 인증 토큰은 OkHttp Interceptor 가 자동 첨부.
 */
interface HomeApi {
    @GET("home")
    suspend fun getHome(): HomeResponse
}

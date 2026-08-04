package com.aihealthcare.ah0404.network

import retrofit2.http.GET

/**
 * 고객센터(_16) Retrofit 계약 — dev 백엔드 정본(#74). 인증 토큰은 OkHttp Interceptor 가 자동 첨부.
 */
interface SupportApi {
    @GET("support")
    suspend fun getSupport(): SupportResponse

    @GET("support/faqs")
    suspend fun getFaqs(): FaqListResponse
}

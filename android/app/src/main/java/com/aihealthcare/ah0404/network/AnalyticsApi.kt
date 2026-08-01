package com.aihealthcare.ah0404.network

import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 클라이언트 텔레메트리 Retrofit 계약 — dev 백엔드 정본 매핑.
 *
 *  - POST /events/sts-overlay-shown : 근력 기능 안전망 카드(#기록탭 §3.4) 노출 이벤트 1건 수집(201).
 *      주간 발화율의 분자(카드 노출 사용자) 집계용. 실패해도 화면을 막지 않도록 호출부는
 *      fire-and-forget 으로 보낸다(#366).
 */
interface AnalyticsApi {
    @POST("events/sts-overlay-shown")
    suspend fun recordStsOverlayShown(@Body body: StsOverlayShownRequest): StsOverlayShownResponse
}

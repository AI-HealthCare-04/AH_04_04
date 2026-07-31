package com.aihealthcare.ah0404.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH

/** 설정 '내 정보' 신체 정보 조회/편집(#기록탭 §2). 편집은 새 프로필 스냅샷을 만들어 다음 추론부터 반영된다. */
interface HealthProfileApi {
    @GET("health-profiles/me/latest")
    suspend fun getLatest(): HealthProfileLatest

    @PATCH("health-profiles/me")
    suspend fun updateProfile(@Body body: HealthProfilePatchRequest): HealthProfileLatest
}

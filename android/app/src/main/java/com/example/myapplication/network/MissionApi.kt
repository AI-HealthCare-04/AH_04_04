package com.example.myapplication.network

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface MissionApi {
    // 체험하기(게스트) 로그인. 최신 백엔드에서 실제 동작하는 로그인은 guest.
    // (google/kakao는 실제 OAuth 미구현이라 501)
    @POST("auth/guest")
    suspend fun guestLogin(): LoginResponse

    @GET("missions")
    suspend fun getMissions(@Query("status") status: String = "available"): MissionsResponse
}

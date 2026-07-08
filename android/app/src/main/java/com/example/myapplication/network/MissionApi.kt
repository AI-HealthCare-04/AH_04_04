package com.example.myapplication.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface MissionApi {
    @POST("auth/login/google")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @GET("missions")
    suspend fun getMissions(@Query("status") status: String = "available"): MissionsResponse
}

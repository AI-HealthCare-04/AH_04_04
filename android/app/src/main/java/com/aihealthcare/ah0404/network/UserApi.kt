package com.aihealthcare.ah0404.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH

/**
 * `_14 내 정보` 화면용 Retrofit 계약 — dev 백엔드 정본(#67 통합 응답) 매핑.
 *
 *  - GET /users/me   : 계정 정보 + 생일·성별·보유포인트·운동강도 통합(GAP #5).
 *  - PATCH /users/me : 닉네임 변경 후 GET 과 동일한 통합 응답 반환.
 *
 *  인증 토큰은 NetworkClient 의 OkHttp Interceptor 가 자동 첨부한다.
 */
interface UserApi {
    @GET("users/me")
    suspend fun getMe(): UserInfoResponse

    @PATCH("users/me")
    suspend fun updateMe(@Body body: UserUpdateRequest): UserInfoResponse
}

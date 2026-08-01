package com.aihealthcare.ah0404.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH

/**
 * `_14 내 정보` 화면용 Retrofit 계약 — dev 백엔드 정본(#67 통합 응답) 매핑.
 *
 *  - GET /users/me   : 계정 정보 + 생일·성별·보유포인트·운동강도 통합(GAP #5).
 *  - PATCH /users/me : 닉네임 변경 후 GET 과 동일한 통합 응답 반환.
 *  - DELETE /users/me : 회원탈퇴(#356). 204. body 가 필요해 @HTTP(hasBody=true) 를 쓴다.
 *
 *  인증 토큰은 NetworkClient 의 OkHttp Interceptor 가 자동 첨부한다.
 */
interface UserApi {
    @GET("users/me")
    suspend fun getMe(): UserInfoResponse

    @PATCH("users/me")
    suspend fun updateMe(@Body body: UserUpdateRequest): UserInfoResponse

    /**
     * 회원탈퇴(#356). 서버가 연결 데이터를 실제 삭제하고 계정을 익명화한다 —
     * 같은 소셜 계정으로 다시 로그인해도 복구되지 않고 신규 가입이 된다(옵션 2).
     */
    @HTTP(method = "DELETE", path = "users/me", hasBody = true)
    suspend fun withdraw(@Body body: UserWithdrawRequest)
}

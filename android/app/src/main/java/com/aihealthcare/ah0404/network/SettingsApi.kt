package com.aihealthcare.ah0404.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH

/**
 * 설정(_15) Retrofit 계약 — dev 백엔드 정본 매핑.
 *
 *  - GET /users/me/settings   : 현재 설정(글자/소리 크기·펫·배경음악).
 *  - PATCH /users/me/settings : 부분 변경(보낸 필드만). null 은 전송에서 빠져 no-op(서버 exclude_none).
 *      font_size/sound_size 는 서버 enum(small/medium/large) 검증 — 잘못된 값은 422.
 */
interface SettingsApi {
    @GET("users/me/settings")
    suspend fun getSettings(): UserSettingsResponse

    @PATCH("users/me/settings")
    suspend fun updateSettings(@Body body: UserSettingsUpdateRequest): UserSettingsResponse
}

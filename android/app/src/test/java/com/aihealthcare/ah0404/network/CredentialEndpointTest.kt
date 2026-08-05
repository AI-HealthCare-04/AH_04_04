package com.aihealthcare.ah0404.network

import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 401 을 세션 이상으로 볼지 가르는 경로 판정.
 *
 * 종전에는 `Authorization 헤더가 붙었는가`로 갈랐는데, 요청 인터셉터는 **토큰이 비면 헤더를
 * 아예 안 붙인다.** 그래서 토큰 없이 인증 API 를 호출하면 401 이 와도 아무도 보고하지 않았고,
 * 라우팅이 갱신되지 않아 화면이 실패에 고착됐다(홈 제보). 헤더 유무 대신 경로로 가른다.
 */
class CredentialEndpointTest {

    private fun request(path: String): Request =
        Request.Builder().url("https://aigo-health.duckdns.org$path").build()

    @Test
    fun 자격증명_엔드포인트는_세션이상으로_보지_않는다() {
        // 이 경로들의 401 은 "소셜 토큰이 유효하지 않다" — 세션 만료가 아니다.
        assertTrue(request("/api/v1/auth/guest").isCredentialEndpoint())
        assertTrue(request("/api/v1/auth/login/google").isCredentialEndpoint())
        assertTrue(request("/api/v1/auth/login/kakao").isCredentialEndpoint())
    }

    @Test
    fun 그_외_api_의_401_은_세션이상이다() {
        // 헤더가 안 붙은 채로 401 이 와도 여기서 걸러지지 않아야 라우팅이 로그인으로 넘어간다.
        assertFalse(request("/api/v1/home").isCredentialEndpoint())
        assertFalse(request("/api/v1/missions").isCredentialEndpoint())
        assertFalse(request("/api/v1/users/me").isCredentialEndpoint())
        assertFalse(request("/api/v1/risk-predictions/me/latest").isCredentialEndpoint())
    }

    @Test
    fun 사용자_경로에_auth_가_들어가도_자격증명으로_오인하지_않는다() {
        // `startsWith` 로 접두사만 본다 — 경로 중간의 'auth' 는 해당 없음.
        assertFalse(request("/api/v1/users/me/auth-history").isCredentialEndpoint())
        assertFalse(request("/api/v1/support/oauth-guide").isCredentialEndpoint())
    }
}

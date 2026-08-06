package com.aihealthcare.ah0404.network

import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 늦게 도착한 **이전 세션의 401** 이 새 세션을 밀어내지 않는지 고정한다(#435 리뷰 P1).
 *
 *  토큰을 교체해도 그 전에 떠난 요청은 아직 날아다닌다. 그 응답이 뒤늦게 401 로 돌아올 때
 *  그대로 보고하면, **방금 발급받은 게스트·소셜 세션이 즉시 LOGIN_REQUIRED 로 밀린다** —
 *  체험하기를 눌렀는데 다시 로그인 화면이 뜨는 형태라, 이 PR 이 없애려던 증상이 다른 경로로
 *  되살아난다.
 */
class StaleUnauthorizedTest {

    private fun request(token: String?): Request =
        Request.Builder()
            .url("https://aigo-health.duckdns.org/api/v1/home")
            .apply { token?.let { addHeader("Authorization", "Bearer $it") } }
            .build()

    @Before
    fun reset() {
        AuthFailureCoordinator.resetForTest()
        TokenHolder.token = ""
    }

    @After
    fun tearDown() {
        AuthFailureCoordinator.resetForTest()
        TokenHolder.token = ""
    }

    @Test
    fun 이전_토큰으로_나간_요청의_401_은_새_세션을_밀어내지_않는다() {
        // ① 구 세션 요청이 떠난다.
        val inFlight = request("old-token")
        TokenHolder.token = "old-token"

        // ② 그 사이 새 토큰 발급 — 체험하기 게스트 로그인이 이 형태다.
        TokenHolder.token = "new-guest-token"

        // ③ 구 요청의 401 이 뒤늦게 도착 — 이건 지나간 세션의 응답이다.
        assertFalse(
            "이전 토큰으로 나간 요청의 401 은 현재 세션과 무관하다",
            inFlight.usesCurrentToken(),
        )
    }

    @Test
    fun 현재_토큰으로_나간_요청의_401_은_보고된다() {
        TokenHolder.token = "current-token"
        assertTrue(request("current-token").usesCurrentToken())
    }

    @Test
    fun 토큰이_없는_상태의_헤더없는_요청은_보고_대상이다() {
        // #433 이 메운 구멍 — 토큰이 비어 헤더를 못 붙인 채 나간 요청의 401 도 세션 이상이다.
        //   지금도 비어 있으면 같은 (빈) 세션이므로 걸러내면 안 된다.
        TokenHolder.token = ""
        assertTrue(request(null).usesCurrentToken())
    }

    @Test
    fun 헤더없이_나갔는데_그_사이_토큰이_생겼으면_지나간_세션이다() {
        val inFlight = request(null)   // 토큰이 없던 시점에 떠난 요청
        TokenHolder.token = "token-issued-later"
        assertFalse(inFlight.usesCurrentToken())
    }
}

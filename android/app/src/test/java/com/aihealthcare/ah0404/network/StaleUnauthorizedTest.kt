package com.aihealthcare.ah0404.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 늦게 도착한 **이전 세션의 401** 이 새 세션을 밀어내지 않는지 고정한다(#435 리뷰 P1).
 *
 *  토큰을 교체해도 그 전에 떠난 요청은 아직 날아다닌다. 그 응답이 뒤늦게 401 로 돌아올 때
 *  그대로 보고하면 **방금 발급받은 게스트·소셜 세션이 즉시 LOGIN_REQUIRED 로 밀린다** —
 *  체험하기를 눌렀는데 다시 로그인 화면이 뜨는 형태다.
 *
 *  ⚠️ 판정과 보고가 **서로 다른 연산이면** 그 사이에 새 토큰이 들어오는 TOCTOU 가 남는다
 *  (리뷰 P1 2차). 그래서 코디네이터가 한 임계 구역에서 처리하고, 아래 마지막 테스트가
 *  그 interleaving 을 **래치 결과까지** 검증한다.
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
        TokenHolder.token = "old-token"
        val inFlight = request("old-token")

        TokenHolder.token = "new-guest-token" // 체험하기 게스트 로그인

        assertFalse(
            "지나간 세션의 401 은 보고되지 않는다",
            AuthFailureCoordinator.reportUnauthorizedFor(inFlight.sentToken()),
        )
        assertNull("새 세션의 실패 상태가 서면 안 된다", AuthFailureCoordinator.failure.value)
    }

    @Test
    fun 현재_토큰으로_나간_요청의_401_은_보고된다() {
        TokenHolder.token = "current-token"
        assertTrue(AuthFailureCoordinator.reportUnauthorizedFor(request("current-token").sentToken()))
        assertEquals(AuthFailure.UNAUTHORIZED, AuthFailureCoordinator.failure.value)
    }

    @Test
    fun 토큰이_없는_상태의_헤더없는_요청은_보고_대상이다() {
        // #433 이 메운 구멍 — 토큰이 비어 헤더를 못 붙인 채 나간 요청의 401 도 세션 이상이다.
        TokenHolder.token = ""
        assertTrue(AuthFailureCoordinator.reportUnauthorizedFor(request(null).sentToken()))
    }

    @Test
    fun 헤더없이_나갔는데_그_사이_토큰이_생겼으면_지나간_세션이다() {
        val inFlight = request(null)
        TokenHolder.token = "token-issued-later"
        assertFalse(AuthFailureCoordinator.reportUnauthorizedFor(inFlight.sentToken()))
    }

    @Test
    fun 토큰_교체와_지나간_401_이_교차해도_새_세션은_유지된다() {
        // 리뷰 P1 2차: 확인과 보고가 분리돼 있으면 "비교 통과 → (새 토큰 도착) → 보고" 순서로
        //   새 세션의 래치가 선다. 두 연산을 같은 잠금에 묶었는지를 실제 교차로 검증한다.
        val stale = request("old-token").sentToken()

        repeat(300) { round ->
            AuthFailureCoordinator.resetForTest()
            TokenHolder.token = "old-token"

            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val reporter = Thread {
                start.await()
                AuthFailureCoordinator.reportUnauthorizedFor(stale)
                done.countDown()
            }
            val rotator = Thread {
                start.await()
                TokenHolder.token = "new-token-$round"
                done.countDown()
            }
            reporter.start(); rotator.start()
            start.countDown()
            assertTrue("교차 실행이 끝나야 한다", done.await(5, TimeUnit.SECONDS))

            // 교체가 먼저면 지나간 401 은 무시되고, 401 이 먼저면 교체가 래치를 내린다.
            //   어느 순서든 **새 토큰이 자리잡은 뒤에는 실패 상태가 남아 있으면 안 된다.**
            assertNull(
                "round=$round — 새 세션이 지나간 401 로 밀려났다",
                AuthFailureCoordinator.failure.value,
            )
        }
    }
}

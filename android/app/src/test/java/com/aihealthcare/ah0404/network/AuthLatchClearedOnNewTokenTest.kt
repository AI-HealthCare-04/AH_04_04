package com.aihealthcare.ah0404.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 401 래치가 **새 토큰을 받으면 반드시 내려가는지** 고정한다.
 *
 *  `reportUnauthorized()` 는 single-flight 라 한 번 서면 `onAuthenticated()` 로만 내려간다.
 *  그런데 그 호출이 `SessionStore` 에만 있어서, 토큰을 `TokenHolder` 에 직접 넣는 경로
 *  (체험하기 게스트 로그인·앱 시작 시 복원·헤드리스 데모)가 래치를 선 채로 지나갔다.
 *
 *  래치가 남으면 **이후 401 이 전부 조용히 버려진다** — 라우팅이 안 움직여 화면이 실패에
 *  고착된다. 심사위원이 '체험하기'로 앱을 보는 경로가 정확히 여기에 걸린다.
 */
class AuthLatchClearedOnNewTokenTest {

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
    fun 새_토큰을_받으면_래치가_내려가_다음_401_이_다시_보고된다() {
        // ① 첫 401 — 정상 보고된다.
        assertTrue("첫 401 은 보고돼야 한다", AuthFailureCoordinator.reportUnauthorized())
        assertEquals(AuthFailure.UNAUTHORIZED, AuthFailureCoordinator.failure.value)

        // ② 같은 래치 상태에서의 두 번째 401 은 중복이라 보고하지 않는다(single-flight 의 의도).
        assertTrue("중복 401 은 삼킨다", !AuthFailureCoordinator.reportUnauthorized())

        // ③ 새 토큰 발급 — 체험하기 게스트 로그인이 정확히 이 형태다.
        //    SessionStore.applyLogin 을 타지 않으므로, 이 대입 자체가 래치를 내려야 한다.
        TokenHolder.token = "new-guest-token"
        assertNull("새 인증이 성립했으므로 실패 상태가 남으면 안 된다", AuthFailureCoordinator.failure.value)

        // ④ 이후의 401 은 **다시** 보고돼야 한다. 이게 안 되면 라우팅이 영영 안 움직인다.
        assertTrue(
            "새 토큰 뒤의 401 은 다시 보고돼야 라우팅이 로그인으로 넘어간다",
            AuthFailureCoordinator.reportUnauthorized(),
        )
        assertEquals(AuthFailure.UNAUTHORIZED, AuthFailureCoordinator.failure.value)
    }

    @Test
    fun 빈_토큰_대입은_인증_성립이_아니다() {
        AuthFailureCoordinator.reportUnauthorized()
        assertEquals(AuthFailure.UNAUTHORIZED, AuthFailureCoordinator.failure.value)

        // 로그아웃·복원 실패로 토큰을 비우는 경로. 인증이 성립한 게 아니라 사라진 것이므로
        //   실패 상태를 임의로 지우면 안 된다(정리는 SessionStore.clearAuthentication 이 한다).
        TokenHolder.token = ""

        assertEquals(
            "빈 토큰은 래치를 내리지 않는다",
            AuthFailure.UNAUTHORIZED,
            AuthFailureCoordinator.failure.value,
        )
    }

    @Test
    fun 네트워크_실패_상태도_새_토큰으로_해소된다() {
        AuthFailureCoordinator.reportNetworkFailure()
        assertEquals(AuthFailure.NETWORK, AuthFailureCoordinator.failure.value)

        TokenHolder.token = "token-after-recovery"

        // 토큰을 새로 받았다는 건 서버까지 왕복이 성공했다는 뜻이라 연결 실패 상태도 유효하지 않다.
        assertNull(AuthFailureCoordinator.failure.value)
    }
}

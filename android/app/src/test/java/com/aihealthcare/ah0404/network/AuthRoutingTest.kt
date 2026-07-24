package com.aihealthcare.ah0404.network

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthRoutingTest {
    private val now = 2_000L

    @Test
    fun tokenInspector_classifiesMissingMalformedExpiredAndValidTokens() {
        assertEquals(TokenStatus.MISSING, JwtTokenInspector.inspect("", now))
        assertEquals(TokenStatus.MALFORMED, JwtTokenInspector.inspect("not-a-jwt", now))
        assertEquals(TokenStatus.MALFORMED, JwtTokenInspector.inspect(jwt("{}"), now))
        assertEquals(TokenStatus.EXPIRED, JwtTokenInspector.inspect(jwt("{\"exp\":1999}"), now))
        assertEquals(TokenStatus.EXPIRED, JwtTokenInspector.inspect(jwt("{\"exp\":2000}"), now))
        assertEquals(TokenStatus.VALID, JwtTokenInspector.inspect(jwt("{\"exp\":2001}"), now))
    }

    @Test
    fun completedUser_withMissingOrMalformedToken_routesToLogin() {
        assertRoute(TokenStatus.MISSING, true, null, AppRoute.LOGIN_REQUIRED)
        assertRoute(TokenStatus.MALFORMED, false, null, AppRoute.LOGIN_REQUIRED)
    }

    @Test
    fun expiredToken_routesByConnectivityWithoutDeletingDecisionHere() {
        assertRoute(TokenStatus.EXPIRED, true, null, AppRoute.LOGIN_REQUIRED)
        assertRoute(TokenStatus.EXPIRED, false, null, AppRoute.OFFLINE)
    }

    @Test
    fun transientFailures_routeOffline_butUnauthorizedRoutesToLogin() {
        assertRoute(TokenStatus.VALID, true, AuthFailure.NETWORK, AppRoute.OFFLINE)
        assertRoute(TokenStatus.VALID, true, AuthFailure.SERVER, AppRoute.OFFLINE)
        assertRoute(TokenStatus.VALID, true, AuthFailure.UNAUTHORIZED, AppRoute.LOGIN_REQUIRED)
    }

    @Test
    fun incompleteOnboarding_hasHighestPriority() {
        val route = AppRouteResolver.resolve(
            onboardingCompleted = false,
            tokenStatus = TokenStatus.MISSING,
            networkAvailable = false,
            failure = AuthFailure.UNAUTHORIZED,
        )
        assertEquals(AppRoute.ONBOARDING, route)
    }

    @Test
    fun walkingActive_bypassesOfflineOnNetworkFailure_butNotAuthOrTokenIssues() {
        // #188: 걷기 측정 중엔 네트워크/서버 단절로 OFFLINE 로 튕기지 않고 MAIN 유지(측정 지속, 저장만 재시도 #91).
        assertEquals(AppRoute.MAIN, resolveWalking(TokenStatus.VALID, online = false, failure = null))
        assertEquals(AppRoute.MAIN, resolveWalking(TokenStatus.VALID, online = true, failure = AuthFailure.NETWORK))
        assertEquals(AppRoute.MAIN, resolveWalking(TokenStatus.VALID, online = true, failure = AuthFailure.SERVER))

        // 그러나 인증만료(401)는 측정 중이어도 가로채지 않고 재로그인으로 보낸다.
        assertEquals(AppRoute.LOGIN_REQUIRED, resolveWalking(TokenStatus.VALID, online = true, failure = AuthFailure.UNAUTHORIZED))
        // 토큰 만료/부재도 측정 중 우회 대상이 아니다(재로그인 필요).
        assertEquals(AppRoute.LOGIN_REQUIRED, resolveWalking(TokenStatus.EXPIRED, online = true, failure = null))
        assertEquals(AppRoute.LOGIN_REQUIRED, resolveWalking(TokenStatus.MISSING, online = false, failure = null))

        // 측정 중이 아니면(기본 false) 기존 동작 유지: VALID + 오프라인 → OFFLINE.
        assertRoute(TokenStatus.VALID, false, null, AppRoute.OFFLINE)
    }

    private fun resolveWalking(tokenStatus: TokenStatus, online: Boolean, failure: AuthFailure?): AppRoute =
        AppRouteResolver.resolve(
            onboardingCompleted = true,
            tokenStatus = tokenStatus,
            networkAvailable = online,
            failure = failure,
            walkingActive = true,
        )

    private fun assertRoute(
        tokenStatus: TokenStatus,
        online: Boolean,
        failure: AuthFailure?,
        expected: AppRoute,
    ) {
        assertEquals(
            expected,
            AppRouteResolver.resolve(
                onboardingCompleted = true,
                tokenStatus = tokenStatus,
                networkAvailable = online,
                failure = failure,
            ),
        )
    }

    private fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"none\"}".toByteArray())
        val body = encoder.encodeToString(payload.toByteArray())
        return "$header.$body.signature"
    }
}

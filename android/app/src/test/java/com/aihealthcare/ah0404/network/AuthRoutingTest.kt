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

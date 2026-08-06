package com.aihealthcare.ah0404.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthFailureCoordinatorTest {
    @Before
    fun setUp() = AuthFailureCoordinator.resetForTest()

    @After
    fun tearDown() = AuthFailureCoordinator.resetForTest()

    @Test
    fun unauthorized_isSingleFlightUntilAuthenticationSucceeds() {
        assertTrue(AuthFailureCoordinator.reportUnauthorizedFor(TokenHolder.token))
        assertFalse(AuthFailureCoordinator.reportUnauthorizedFor(TokenHolder.token))
        assertEquals(AuthFailure.UNAUTHORIZED, AuthFailureCoordinator.failure.value)

        AuthFailureCoordinator.onAuthenticated()

        assertTrue(AuthFailureCoordinator.reportUnauthorizedFor(TokenHolder.token))
    }

    @Test
    fun transientFailure_neverOverwritesUnauthorized() {
        AuthFailureCoordinator.reportUnauthorizedFor(TokenHolder.token)
        AuthFailureCoordinator.reportNetworkFailure()
        AuthFailureCoordinator.reportServerFailure()

        assertEquals(AuthFailure.UNAUTHORIZED, AuthFailureCoordinator.failure.value)
    }

    @Test
    fun networkRecovery_clearsOnlyNetworkFailure() {
        AuthFailureCoordinator.reportNetworkFailure()
        AuthFailureCoordinator.onNetworkAvailable()
        assertNull(AuthFailureCoordinator.failure.value)

        AuthFailureCoordinator.reportServerFailure()
        AuthFailureCoordinator.onNetworkAvailable()
        assertEquals(AuthFailure.SERVER, AuthFailureCoordinator.failure.value)
    }

    @Test
    fun successfulRetry_clearsTransientFailureButNotUnauthorized() {
        AuthFailureCoordinator.reportServerFailure()
        AuthFailureCoordinator.onRequestSucceeded()
        assertNull(AuthFailureCoordinator.failure.value)

        AuthFailureCoordinator.reportUnauthorizedFor(TokenHolder.token)
        AuthFailureCoordinator.onRequestSucceeded()
        assertEquals(AuthFailure.UNAUTHORIZED, AuthFailureCoordinator.failure.value)
    }
}

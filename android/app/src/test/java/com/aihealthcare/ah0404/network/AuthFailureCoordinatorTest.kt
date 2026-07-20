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
        assertTrue(AuthFailureCoordinator.reportUnauthorized())
        assertFalse(AuthFailureCoordinator.reportUnauthorized())
        assertEquals(AuthFailure.UNAUTHORIZED, AuthFailureCoordinator.failure.value)

        AuthFailureCoordinator.onAuthenticated()

        assertTrue(AuthFailureCoordinator.reportUnauthorized())
    }

    @Test
    fun transientFailure_neverOverwritesUnauthorized() {
        AuthFailureCoordinator.reportUnauthorized()
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

        AuthFailureCoordinator.reportUnauthorized()
        AuthFailureCoordinator.onRequestSucceeded()
        assertEquals(AuthFailure.UNAUTHORIZED, AuthFailureCoordinator.failure.value)
    }
}

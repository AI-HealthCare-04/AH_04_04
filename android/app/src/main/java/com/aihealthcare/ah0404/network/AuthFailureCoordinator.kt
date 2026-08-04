package com.aihealthcare.ah0404.network

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AuthFailure {
    UNAUTHORIZED,
    NETWORK,
    SERVER,
}

/**
 * 여러 API가 동시에 401을 반환해도 인증 만료 상태를 한 번만 발행한다.
 * 새 인증이 저장되기 전까지 single-flight 잠금을 유지해 로그인 이동 루프를 막는다.
 */
object AuthFailureCoordinator {
    private val unauthorizedInFlight = AtomicBoolean(false)
    private val mutableFailure = MutableStateFlow<AuthFailure?>(null)
    val failure: StateFlow<AuthFailure?> = mutableFailure.asStateFlow()

    fun reportUnauthorized(): Boolean {
        if (!unauthorizedInFlight.compareAndSet(false, true)) return false
        mutableFailure.value = AuthFailure.UNAUTHORIZED
        return true
    }

    fun reportNetworkFailure() {
        if (!unauthorizedInFlight.get()) mutableFailure.value = AuthFailure.NETWORK
    }

    fun reportServerFailure() {
        if (!unauthorizedInFlight.get()) mutableFailure.value = AuthFailure.SERVER
    }

    fun onNetworkAvailable() {
        if (mutableFailure.value == AuthFailure.NETWORK) mutableFailure.value = null
    }

    fun onRequestSucceeded() {
        if (mutableFailure.value != AuthFailure.UNAUTHORIZED) mutableFailure.value = null
    }

    fun retryTransientFailure() {
        if (mutableFailure.value != AuthFailure.UNAUTHORIZED) mutableFailure.value = null
    }

    fun onAuthenticated() {
        unauthorizedInFlight.set(false)
        mutableFailure.value = null
    }

    internal fun resetForTest() = onAuthenticated()
}

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
 *
 * **현재 토큰도 여기서 관리한다**(리뷰 P1 2차). 토큰 교체와 401 보고가 서로 다른 연산이면
 * "요청 토큰이 현재 토큰인가"를 확인한 뒤 보고하기 직전에 새 토큰이 들어오는 TOCTOU 가 남는다.
 * 그러면 방금 발급받은 세션의 래치를 지나간 401 이 세워 버린다. 두 연산을 같은 잠금 안에 둔다.
 */
object AuthFailureCoordinator {
    private val lock = Any()
    private val unauthorizedInFlight = AtomicBoolean(false)
    private val mutableFailure = MutableStateFlow<AuthFailure?>(null)
    val failure: StateFlow<AuthFailure?> = mutableFailure.asStateFlow()

    /** 현재 세션 토큰의 사본. [TokenHolder] 세터가 유일한 갱신 경로다. */
    private var currentToken: String = ""

    /**
     * 토큰 교체를 반영한다. 비어 있지 않으면 **새 인증이 성립한 것**이므로 실패 상태를 함께 지운다.
     * [reportUnauthorizedFor] 와 같은 잠금을 써서, 확인과 보고 사이에 끼어들 수 없게 한다.
     */
    fun onTokenChanged(value: String) = synchronized(lock) {
        currentToken = value
        if (value.isNotBlank()) {
            unauthorizedInFlight.set(false)
            mutableFailure.value = null
        }
    }

    /**
     * [requestToken] 이 **아직 현재 토큰일 때만** 401 을 보고한다.
     *
     * 토큰을 교체해도 그 전에 떠난 요청은 아직 날아다니고, 그 응답이 뒤늦게 401 로 돌아온다.
     * 지나간 세션의 401 로 새 세션을 밀어내지 않도록 확인과 보고를 한 임계 구역에서 처리한다.
     */
    fun reportUnauthorizedFor(requestToken: String): Boolean = synchronized(lock) {
        if (requestToken != currentToken) return@synchronized false
        if (!unauthorizedInFlight.compareAndSet(false, true)) return@synchronized false
        mutableFailure.value = AuthFailure.UNAUTHORIZED
        true
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

    fun onAuthenticated() = synchronized(lock) {
        unauthorizedInFlight.set(false)
        mutableFailure.value = null
    }

    internal fun resetForTest() = synchronized(lock) {
        currentToken = ""
        unauthorizedInFlight.set(false)
        mutableFailure.value = null
    }
}

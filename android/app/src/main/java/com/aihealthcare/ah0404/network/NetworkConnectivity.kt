package com.aihealthcare.ah0404.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 인터넷 사용 가능 여부(라우팅용). **기본(활성) 네트워크**를 추적한다.
 *
 * ⚠️ 이전 버그: `registerNetworkCallback(INTERNET)` + `activeNetwork` 조합에 **`NET_CAPABILITY_VALIDATED`
 *    강제** → **wifi 를 끄고 다른 망(셀룰러 LTE·5G 등, 또는 다른 wifi)으로 전환하면 우리 앱만 "인터넷 없음"**
 *    이 됐다(다른 앱은 그 망으로 정상 동작). LTE 특정 버그가 아니라 **모든 망 전환/검증도장 문제**다
 *    (재현은 wifi→LTE 로 관측됨).
 *    원인 (1) 기본 네트워크 전환을 제때 못 잡고, (2) 새 기본 네트워크가 아직/영영 VALIDATED 도장을
 *    못 받는 기기·통신사·망에서 하드 게이트에 걸린다.
 * → 수정: `registerDefaultNetworkCallback` 으로 **기본 네트워크 전환을 정확히 추적**하고, 판정은
 *    `NET_CAPABILITY_INTERNET`(인터넷 가능 네트워크가 붙어 있는가)만 본다. **VALIDATED(실제 검증 도장)는
 *    강제하지 않는다** — 서버에 실제로 못 닿는 경우는 요청 단계에서 AuthFailure(NETWORK/SERVER)로 잡아
 *    OFFLINE 으로 보내므로, 여기서 미리 막을 필요가 없다.
 */
@SuppressLint("MissingPermission")
@Composable
fun rememberNetworkAvailable(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    val connectivityManager = remember {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val available = remember { mutableStateOf(connectivityManager.hasInternet()) }

    DisposableEffect(connectivityManager) {
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            // 새 기본 네트워크가 붙는 순간(오프라인 시작 → 연결, wifi→cellular 전환 등). onCapabilitiesChanged 가
            // 늘 뒤따르지만, API 24~25 에서 그 도착을 놓칠 수 있어 여기서도 처리한다(지영 리뷰 #185).
            //   ⚠️ 여기서 getNetworkCapabilities 동기 조회는 하지 않는다 — 이 시점엔 caps 가 아직 안 실려
            //   레이스가 된다(공식 문서). 기본 네트워크가 붙었으면 '사용 가능'으로 두고, INTERNET 유무 정밀
            //   판정은 뒤따르는 onCapabilitiesChanged 에 맡긴다(그게 늦거나 누락돼도 최소한 온라인으로 잡힘).
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    available.value = true
                    AuthFailureCoordinator.onNetworkAvailable()
                }
            }

            // 기본 네트워크의 능력이 바뀌거나(붙음) 다른 네트워크(wifi→cellular)로 전환되면 새 기본 네트워크로 호출된다.
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                mainHandler.post {
                    val ok = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    available.value = ok
                    if (ok) AuthFailureCoordinator.onNetworkAvailable()
                }
            }

            // 기본 네트워크가 사라지고 **대체 네트워크가 없을 때만** 호출된다(= 진짜 오프라인).
            // wifi→cellular 전환은 새 기본 네트워크로 onCapabilitiesChanged 가 오지 onLost 가 오지 않는다.
            override fun onLost(network: Network) {
                mainHandler.post { available.value = false }
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        onDispose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    return available
}

/** 현재 기본 네트워크가 인터넷 가능 네트워크인가(초기값용). 실제 서버 도달 여부는 요청이 최종 판정. */
@SuppressLint("MissingPermission")
private fun ConnectivityManager.hasInternet(): Boolean {
    val capabilities = getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

package com.aihealthcare.ah0404.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@SuppressLint("MissingPermission")
@Composable
fun rememberNetworkAvailable(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    val connectivityManager = remember {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val available = remember { mutableStateOf(connectivityManager.isNetworkAvailable()) }

    DisposableEffect(connectivityManager) {
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    available.value = connectivityManager.isNetworkAvailable()
                    if (available.value) AuthFailureCoordinator.onNetworkAvailable()
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post { available.value = connectivityManager.isNetworkAvailable() }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                mainHandler.post {
                    available.value = connectivityManager.isNetworkAvailable()
                    if (available.value) AuthFailureCoordinator.onNetworkAvailable()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
        onDispose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    return available
}

@SuppressLint("MissingPermission")
private fun ConnectivityManager.isNetworkAvailable(): Boolean {
    val capabilities = getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

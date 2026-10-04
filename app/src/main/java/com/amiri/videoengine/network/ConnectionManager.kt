package com.amiri.videoengine.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionState(val label: String) {
    ONLINE("Online"),
    LIMITED("Limited connection"),
    OFFLINE("Offline"),
}

/**
 * Listens to Android's own connectivity callbacks. It never pings the internet,
 * so it costs no data.
 */
class ConnectionManager(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(compute())
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _state.value = compute() }
        override fun onLost(network: Network) { _state.value = compute() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _state.value = compute()
        }
    }

    init {
        try {
            cm?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // Fall back to on-demand checks.
        }
    }

    fun current(): ConnectionState {
        val now = compute()
        _state.value = now
        return now
    }

    private fun compute(): ConnectionState {
        val manager = cm ?: return ConnectionState.OFFLINE
        val network = manager.activeNetwork ?: return ConnectionState.OFFLINE
        val caps = manager.getNetworkCapabilities(network) ?: return ConnectionState.OFFLINE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return ConnectionState.OFFLINE
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            ConnectionState.ONLINE
        } else {
            ConnectionState.LIMITED
        }
    }
}

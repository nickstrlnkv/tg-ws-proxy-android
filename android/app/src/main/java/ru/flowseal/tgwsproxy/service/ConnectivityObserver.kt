package ru.flowseal.tgwsproxy.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.Closeable

class ConnectivityObserver(
    private val context: Context,
    private val onNetworkChanged: () -> Unit
) : Closeable {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (connectivityManager == null) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkChanged()
            }

            override fun onLost(network: Network) {
                onNetworkChanged()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                onNetworkChanged()
            }
        }

        callback = cb
        try {
            connectivityManager.registerNetworkCallback(request, cb)
        } catch (_: Exception) {}
    }

    override fun close() {
        val cb = callback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        callback = null
    }
}

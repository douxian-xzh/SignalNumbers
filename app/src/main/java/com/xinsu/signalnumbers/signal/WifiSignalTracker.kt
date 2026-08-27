package com.xinsu.signalnumbers.signal

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

@SuppressLint("MissingPermission") // Calls run in com.android.systemui, which owns the required network permissions.
class WifiSignalTracker(
    private val context: Context,
    private val onChanged: (WifiReading) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private var reading = WifiReading()
    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshFromNetwork(network)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateFromCapabilities(capabilities)
        override fun onLost(network: Network) = refresh()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val direct = intent?.takeIf { it.action == WifiManager.RSSI_CHANGED_ACTION }
                ?.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -127)
                ?.takeIf(::validRssi)
            if (direct != null) update(WifiReading(true, direct)) else refresh()
        }
    }

    fun start() = guarded {
        if (registered) return@guarded
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(request, networkCallback)
        val filter = IntentFilter().apply {
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        registered = true
        refresh()
    }

    fun refresh() = guarded {
        val active = connectivity.activeNetwork
        val caps = active?.let(connectivity::getNetworkCapabilities)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            updateFromCapabilities(caps)
            if (reading.rssi == null) fallbackWifiManager()
        } else {
            val wifiNetwork = connectivity.allNetworks.firstOrNull {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifiNetwork != null) refreshFromNetwork(wifiNetwork) else update(WifiReading(false, null))
        }
    }

    /** Preferred source when an OEM/AOSP SystemUI state object exposes a real RSSI field. */
    fun acceptSystemUiRssi(rssi: Int) {
        if (validRssi(rssi)) update(WifiReading(true, rssi))
    }

    fun stop() = guarded {
        if (!registered) return@guarded
        connectivity.unregisterNetworkCallback(networkCallback)
        context.unregisterReceiver(receiver)
        registered = false
    }

    private fun refreshFromNetwork(network: Network): Unit {
        guarded {
            val capabilities = connectivity.getNetworkCapabilities(network)
            if (capabilities != null) updateFromCapabilities(capabilities) else refresh()
        }
    }

    private fun updateFromCapabilities(caps: NetworkCapabilities) {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        val info = caps.transportInfo as? WifiInfo
        val rssi = info?.rssi?.takeIf(::validRssi)
            ?: caps.signalStrength.takeIf(::validRssi)
        update(WifiReading(true, rssi))
        if (rssi == null) fallbackWifiManager()
    }

    @Suppress("DEPRECATION")
    private fun fallbackWifiManager() {
        val info = wifiManager.connectionInfo
        val connected = info.networkId != -1 || info.supplicantState == android.net.wifi.SupplicantState.COMPLETED
        update(WifiReading(connected, info.rssi.takeIf(::validRssi)))
    }

    private fun validRssi(value: Int) = value in -126..-1

    private fun update(newValue: WifiReading) {
        if (newValue == reading) return
        reading = newValue
        if (powerManager.isInteractive) onChanged(newValue)
    }

    private inline fun guarded(block: () -> Unit) {
        runCatching(block).onFailure(onError)
    }
}

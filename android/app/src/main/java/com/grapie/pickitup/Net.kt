package com.grapie.pickitup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether this app's own traffic goes through a VPN. Android gives every app its default
 * network per UID: with split tunneling that leaves PickItUp! out, it is the plain Wi-Fi /
 * mobile network, so only a VPN that really carries our requests counts.
 */
object Net {
    /** For the UI; [vpnActive] is the exact check before a request. */
    val vpn = MutableStateFlow(false)

    fun init(context: Context) {
        if (!context.resources.getBoolean(R.bool.vpn_guard)) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        vpn.value = vpnActive(context)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    vpn.value = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }

                override fun onLost(network: Network) {
                    vpn.value = false
                }
            })
        }
    }

    fun vpnActive(context: Context): Boolean {
        if (!context.resources.getBoolean(R.bool.vpn_guard)) return false
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}

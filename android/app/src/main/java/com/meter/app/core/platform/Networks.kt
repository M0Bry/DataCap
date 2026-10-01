package com.meter.app.core.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Small wrapper around [ConnectivityManager] that answers the only three questions the meter
 * asks: which interface is carrying traffic right now, over which transport, and — while our
 * optional SIM valve is up — what the *real* connection underneath the tunnel is.
 */
class Networks(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    enum class Transport { WIFI, CELLULAR, ETHERNET, VPN, NONE }

    /**
     * The network that really carries traffic.
     *
     * While the SIM valve is engaged the active network is our own VPN, and Android exposes no
     * public getter for a VPN's underlying network, so the best available network is picked by the
     * same priority the system routes with: Wi-Fi, then Ethernet, then cellular. That keeps the
     * live-speed readout and the valve's own state machine correct during a block.
     */
    fun activeNetwork(): Network? {
        val manager = cm ?: return null
        val active = manager.activeNetwork ?: return null
        val caps = manager.getNetworkCapabilities(active) ?: return active
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return active
        return manager.allNetworks
            .filter { it != active }
            .mapNotNull { network ->
                val c = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                val rank = rankOf(c)
                if (rank == 0) null else network to rank
            }
            .maxByOrNull { it.second }
            ?.first
    }

    fun activeTransport(): Transport {
        val manager = cm ?: return Transport.NONE
        val network = activeNetwork() ?: return Transport.NONE
        val caps = manager.getNetworkCapabilities(network) ?: return Transport.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            else -> Transport.NONE
        }
    }

    fun activeInterface(): String? = runCatching {
        val manager = cm ?: return null
        val network = activeNetwork() ?: return null
        manager.getLinkProperties(network)?.interfaceName
    }.getOrElse {
        Log.d(TAG, "activeInterface failed: ${it.message}")
        null
    }

    /** Every interface the system currently knows about, with its transport. */
    fun interfaceTransports(): Map<String, Transport> = runCatching {
        val manager = cm ?: return emptyMap()
        val out = LinkedHashMap<String, Transport>()
        manager.allNetworks.forEach { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@forEach
            val iface = manager.getLinkProperties(network)?.interfaceName ?: return@forEach
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
                else -> Transport.NONE
            }
            // Never let a VPN overwrite the physical interface it depends on.
            if (transport != Transport.NONE && out[iface] == null) out[iface] = transport
        }
        out
    }.getOrElse { emptyMap() }

    fun isConnected(): Boolean = activeNetwork() != null

    private fun rankOf(caps: NetworkCapabilities): Int = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
        else -> 0
    }

    private companion object {
        const val TAG = "Meter.Networks"
    }
}

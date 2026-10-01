package com.meter.app.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.meter.app.core.platform.InterfaceCounters
import com.meter.app.core.platform.Networks
import com.meter.app.core.platform.SimIdentity

/**
 * Discovers the device's internet sources — **one source per SIM, one source per Wi-Fi network**,
 * never a merged "mobile" or "wifi" bucket.
 *
 * SIMs come from the subscription list, so SIM 1 and SIM 2 exist as two entries as soon as both
 * are inserted, whether or not they are currently carrying data. Which of them a byte belongs to
 * is decided per sample by [SimIdentity.owners].
 *
 * Wi-Fi networks come from every Wi-Fi [android.net.Network] the system knows, plus the SSID the
 * phone is associated with right now, plus every SSID the phone has been seen on before (so a
 * network's quota and counters survive leaving it).
 *
 * Everything is best-effort: a missing permission or an OEM quirk degrades the naming, never the
 * metering.
 */
class SourceRegistry(private val context: Context) {

    private val cm: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val identity = SimIdentity(context)
    private val networks = Networks(context)

    /** Interfaces that can carry traffic, in the order the counter layer should watch them. */
    fun uplinks(): List<String> = InterfaceCounters().uplinkInterfaces()

    fun simSlots(uplinks: List<String> = uplinks()): List<SimIdentity.SimSlot> = identity.sims(uplinks)

    fun discover(knownWifi: List<String> = emptyList()): List<Source> {
        val uplinks = uplinks()
        val sources = ArrayList<Source>(6)

        // ------------------------------------------------------------------ SIMs
        val sims = identity.sims(uplinks)
        val cellularUplinks = uplinks.filter { InterfaceCounters.isCellular(it) }
        sims.forEach { sim ->
            sources += Source(
                id = sim.sourceId,
                type = SourceType.SIM,
                name = sim.carrier.ifBlank { "SIM ${sim.slot + 1}" },
                detail = "SIM ${sim.slot + 1}",
                iface = sim.iface.ifBlank { cellularUplinks.firstOrNull().orEmpty() },
                subId = sim.subId,
                slot = sim.slot,
                carrier = sim.carrier,
                subscriberId = sim.subscriberId
            )
        }
        if (sources.none { it.type == SourceType.SIM }) {
            // No telephony information at all: still meter the radio, name it generically.
            sources += Source(
                id = "sim:0",
                type = SourceType.SIM,
                name = "Mobile data",
                detail = "SIM",
                iface = cellularUplinks.firstOrNull().orEmpty()
            )
        }

        // ---------------------------------------------------------------- Wi-Fi
        val wifiSeen = LinkedHashSet<String>()
        wifiNetworks().forEach { src -> if (wifiSeen.add(src.id)) sources += src }
        currentWifi()?.let { src -> if (wifiSeen.add(src.id)) sources += src }
        knownWifi.forEach { line ->
            val parts = line.split('|')
            if (parts.size < 4) return@forEach
            if (wifiSeen.add(parts[0])) {
                sources += Source(
                    id = parts[0], type = SourceType.WIFI, name = parts[1],
                    detail = parts[3], iface = parts[2].ifBlank { "wlan0" }
                )
            }
        }
        if (sources.none { it.type == SourceType.WIFI }) {
            sources += Source(
                id = "wifi:<none>", type = SourceType.WIFI, name = "Wi-Fi",
                detail = "not connected", iface = wlanInterface() ?: "wlan0"
            )
        }

        return sources.sortedWith(compareBy({ it.type.ordinal }, { it.slot }, { it.name }))
    }

    /** The Wi-Fi source the phone is associated with right now, if the SSID is readable. */
    fun activeWifiSourceId(): String? = currentWifi()?.id

    fun activeWifiSource(current: List<Source>): Source? {
        val id = activeWifiSourceId() ?: return null
        return current.firstOrNull { it.id == id }
    }

    /** `id|name|iface|detail` for every Wi-Fi source, ready to be persisted. */
    fun wifiPersistenceLines(sources: List<Source>): List<String> =
        sources.filter { it.type == SourceType.WIFI && !it.id.startsWith("wifi:<") }
            .map { "${it.id}|${it.name}|${it.iface}|${it.detail}" }

    /**
     * The live ownership map: interface → the one source that owns it at this instant.
     * This is what keeps SIM 1 and SIM 2 apart even when they share a single uplink.
     */
    fun ownership(
        sources: List<Source>,
        uplinks: List<String> = uplinks()
    ): Map<String, String> = identity.owners(
        uplinks = uplinks,
        sims = identity.sims(uplinks),
        activeWifiSourceId = activeWifiSourceId(),
        defaultWifiSourceId = sources.firstOrNull { it.type == SourceType.WIFI }?.id,
        extraUplinks = sources.mapNotNull { it.iface.takeIf { i -> i.isNotBlank() } }
    )

    fun hasPerSimInterfaces(uplinks: List<String> = uplinks()): Boolean =
        identity.hasPerSimInterfaces(uplinks)

    // -------------------------------------------------------------------- wifi

    private fun wifiNetworks(): List<Source> {
        val manager = cm ?: return emptyList()
        val out = ArrayList<Source>(2)
        manager.allNetworks.forEach { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@forEach
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@forEach
            val link = manager.getLinkProperties(network) ?: return@forEach
            wifiSource(caps, link)?.let { out += it }
        }
        return out
    }

    private fun currentWifi(): Source? {
        val manager = cm ?: return null
        val active = networks.activeNetwork() ?: return null
        val caps = manager.getNetworkCapabilities(active) ?: return null
        val link = manager.getLinkProperties(active) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        return wifiSource(caps, link)
    }

    private fun wifiSource(caps: NetworkCapabilities, link: LinkProperties): Source? {
        val ssid = ssidOf(caps, link) ?: return null
        return Source(
            id = "wifi:$ssid",
            type = SourceType.WIFI,
            name = ssid,
            detail = bandLabel(frequencyOf(caps)),
            iface = link.interfaceName ?: wlanInterface() ?: "wlan0"
        )
    }

    /**
     * The SSID is only readable with location permission from Android 8.1 on; without it the OS
     * redacts it. We ask for the permission in the UI and fall back to a stable generic name so
     * metering never stops because of naming.
     */
    private fun ssidOf(caps: NetworkCapabilities, link: LinkProperties): String? {
        val hasLocation = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = caps.transportInfo as? WifiInfo
            if (info != null && hasLocation) cleanSsid(info.ssid)?.let { return it }
        } else if (hasLocation) {
            @Suppress("DEPRECATION")
            val info = wifiManager?.connectionInfo
            if (info != null) cleanSsid(info.ssid)?.let { return it }
        }
        return "Wi-Fi network"
    }

    private fun cleanSsid(raw: String?): String? {
        val trimmed = raw?.trim()?.trim('"') ?: return null
        if (trimmed.isBlank()) return null
        if (trimmed.equals("<unknown ssid>", true)) return null
        if (trimmed.equals("0x", true)) return null
        return trimmed
    }

    private fun frequencyOf(caps: NetworkCapabilities): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val info = caps.transportInfo as? WifiInfo ?: return 0
        return info.frequency
    }

    private fun bandLabel(freqMhz: Int): String = when {
        freqMhz >= 5925 -> "6 GHz"
        freqMhz >= 4900 -> "5 GHz"
        freqMhz in 1..2500 -> "2.4 GHz"
        else -> "Wi-Fi"
    }

    private fun wlanInterface(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()
            ?.firstOrNull { it.name.startsWith("wlan") }?.name
    }.getOrElse {
        Log.d(TAG, "wlanInterface failed: ${it.message}")
        null
    }

    private companion object {
        const val TAG = "Meter.Sources"
    }
}

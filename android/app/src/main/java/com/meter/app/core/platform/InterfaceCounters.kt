package com.meter.app.core.platform

import android.net.TrafficStats
import android.os.Build
import android.util.Log
import com.meter.app.core.ledger.InterfaceReading
import java.io.File
import java.net.NetworkInterface

/**
 * Reads the kernel's per-interface byte counters — the same numbers `netd` hands to Settings →
 * Data usage — with three interchangeable backends and one strict rule: **the backend is chosen
 * once per interface and then never changes**, because mixing sources between samples would make
 * the deltas meaningless.
 *
 *  1. [TrafficStats] — exact, cheap, and the only one that also gives packet counts (API 29+).
 *  2. `/proc/net/dev` — always present, gives bytes *and* packets, and covers the interfaces
 *     where [TrafficStats] returns `UNSUPPORTED` (common for secondary SIMs on some OEMs).
 *  3. `/sys/class/net/<iface>/statistics/` (rx_bytes, tx_bytes, rx_packets, tx_packets) — the fallback for hardened kernels that hide
 *     `/proc/net/dev`.
 *
 * A reading is only used when the interface really is an uplink: loopback, P2P, the legacy
 * Qualcomm `rmnet_ipa` aggregate (which mirrors `rmnet_data*` and would double count) and our own
 * `tun` interface are all excluded on purpose.
 */
class InterfaceCounters {

    enum class Backend { TRAFFIC_STATS, PROC_NET_DEV, SYSFS }

    private val backendOf = HashMap<String, Backend>()
    private var procNetDevCache: Map<String, InterfaceReading>? = null

    fun reading(iface: String): InterfaceReading? {
        val backend = backendOf[iface] ?: pickBackend(iface)?.also { backendOf[iface] = it }
            ?: return null
        val reading = when (backend) {
            Backend.TRAFFIC_STATS -> fromTrafficStats(iface)
            Backend.PROC_NET_DEV -> fromProcNetDev(iface)
            Backend.SYSFS -> fromSysFs(iface)
        }
        // A backend that suddenly stops answering (interface removed, permission change) is
        // dropped so the next sample re-picks one instead of silently reporting nothing.
        if (reading == null) backendOf.remove(iface)
        return reading
    }

    fun readings(ifaces: Collection<String>): List<InterfaceReading> =
        ifaces.mapNotNull { reading(it) }

    /** All uplink interfaces of the device, best-effort and de-duplicated. */
    fun uplinkInterfaces(): List<String> {
        val procNames = procNetDevCache?.keys ?: parseProcNetDev().keys
        val candidates = LinkedHashSet<String>()
        candidates += trafficStatsInterfaces()
        candidates += procNames
        candidates += sysfsInterfaces()
        return selectUplinks(candidates)
    }

    private fun pickBackend(iface: String): Backend? {
        fromTrafficStats(iface)?.let { return Backend.TRAFFIC_STATS }
        if (fromProcNetDev(iface) != null) return Backend.PROC_NET_DEV
        if (fromSysFs(iface) != null) return Backend.SYSFS
        return null
    }

    // ------------------------------------------------------------- traffic stats

    private fun fromTrafficStats(iface: String): InterfaceReading? = runCatching {
        val rx = TrafficStats.getRxBytes(iface)
        val tx = TrafficStats.getTxBytes(iface)
        if (rx < 0 || tx < 0) return null
        val rxP = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) TrafficStats.getRxPackets(iface) else -1L
        val txP = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) TrafficStats.getTxPackets(iface) else -1L
        InterfaceReading(
            iface = iface, rxBytes = rx, txBytes = tx,
            rxPackets = if (rxP < 0) -1L else rxP,
            txPackets = if (txP < 0) -1L else txP
        )
    }.getOrElse {
        Log.d(TAG, "TrafficStats failed for $iface: ${it.message}")
        null
    }

    private fun trafficStatsInterfaces(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()?.map { it.name } ?: emptyList()
    }.getOrElse { emptyList() }

    // -------------------------------------------------------------- /proc/net/dev

    private fun fromProcNetDev(iface: String): InterfaceReading? {
        val all = procNetDevCache ?: parseProcNetDev().also { procNetDevCache = it }
        return all[iface]
    }

    /**
     * `Inter-|   Receive ... |  Transmit` then one line per interface:
     * `rmnet_data0: 1234567 1234 0 0 0 0 0 0 765432 987 0 0 0 0 0 0`
     * (rx: bytes packets errs drop fifo frame compressed multicast
     *  tx: bytes packets errs drop fifo colls carrier compressed)
     */
    internal fun parseProcNetDev(): Map<String, InterfaceReading> = runCatching {
        val file = File("/proc/net/dev")
        if (!file.exists() || !file.canRead()) return emptyMap()
        val out = LinkedHashMap<String, InterfaceReading>()
        file.readLines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@forEach
            val name = line.substring(0, idx).trim()
            val f = line.substring(idx + 1).trim().split(Regex("\\s+"))
            if (name.isEmpty() || f.size < 10) return@forEach
            val rxBytes = f[0].toLongOrNull() ?: return@forEach
            val rxPackets = f[1].toLongOrNull() ?: -1L
            val txBytes = f[8].toLongOrNull() ?: return@forEach
            val txPackets = f[9].toLongOrNull() ?: -1L
            out[name] = InterfaceReading(name, rxBytes, txBytes, rxPackets, txPackets)
        }
        out
    }.getOrElse {
        Log.d(TAG, "/proc/net/dev unavailable: ${it.message}")
        emptyMap()
    }

    // ------------------------------------------------------------------- sysfs

    private fun fromSysFs(iface: String): InterfaceReading? = runCatching {
        val base = File("/sys/class/net/$iface/statistics")
        if (!base.isDirectory) return null
        val rx = readLong(File(base, "rx_bytes")) ?: return null
        val tx = readLong(File(base, "tx_bytes")) ?: return null
        InterfaceReading(
            iface = iface, rxBytes = rx, txBytes = tx,
            rxPackets = readLong(File(base, "rx_packets")) ?: -1L,
            txPackets = readLong(File(base, "tx_packets")) ?: -1L
        )
    }.getOrElse { null }

    private fun sysfsInterfaces(): List<String> = runCatching {
        File("/sys/class/net").listFiles()?.map { it.name } ?: emptyList()
    }.getOrElse { emptyList() }

    private fun readLong(file: File): Long? =
        runCatching { if (file.isFile) file.readText().trim().toLongOrNull() else null }.getOrNull()

    companion object {
        private const val TAG = "Meter.Counters"

        /** Interfaces that can carry internet traffic for one particular source. */
        fun isUplink(name: String): Boolean {
            if (name.isEmpty()) return false
            if (name.startsWith("lo")) return false
            if (name.startsWith("tun")) return false          // our own valve must never be metered
            if (name.startsWith("ppp")) return false
            if (name.startsWith("p2p")) return false
            if (name.startsWith("dummy")) return false
            if (name.contains("ipa")) return false            // Qualcomm aggregate mirrors rmnet_data*
            if (name.startsWith("sit") || name.startsWith("ip6tnl") || name.startsWith("rmnet_ipa")) return false
            return name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") ||
                name.startsWith("seth") || name.startsWith("eth") || name.startsWith("wlan") ||
                name.startsWith("wwan") || name.startsWith("usb") || name.startsWith("v4-") ||
                name.startsWith("clat")
        }

        fun isCellular(name: String): Boolean =
            name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") ||
                name.startsWith("seth") || name.startsWith("wwan") || name.startsWith("clat")

        fun isWifi(name: String): Boolean = name.startsWith("wlan") || name.startsWith("wifi")

        /**
         * Legacy `rmnet0` and modern `rmnet_data0` both exist on some devices and report the same
         * traffic; keeping both would double every byte, so only the modern one survives.
         */
        internal fun selectUplinks(candidates: Collection<String>): List<String> {
            val uplinks = candidates.filter { isUplink(it) }.distinct()
            val dataIfaces = uplinks.filter { it.startsWith("rmnet_data") }
            return uplinks.filter { name ->
                if (!name.matches(Regex("rmnet\\d+"))) return@filter true
                // keep the legacy name only when there is no rmnet_data counterpart
                dataIfaces.isEmpty()
            }.sorted()
        }
    }
}

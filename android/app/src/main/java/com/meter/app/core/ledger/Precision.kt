package com.meter.app.core.ledger

/**
 * What the operator (or the access point) charges on top of the IP payload.
 *
 * Kernel byte counters measure IP packets, so they already include TCP/UDP/TLS/DNS headers,
 * retransmissions and anything the radio re-sends. What they *cannot* see is the framing the
 * modem or the access point adds underneath: RLC/MAC for cellular, 802.11 MAC for Wi-Fi. That
 * framing is billed by the operator and is the usual reason an app's number sits a few percent
 * below the carrier's own figure.
 *
 * [perPacketBytes] is that per-packet allowance; the Setup page exposes it per source and
 * defaults to 36 B for SIMs and 24 B for Wi-Fi, with 0 available for a raw kernel count.
 */
data class OverheadPolicy(
    val perPacketBytes: Int,
    val mtu: Int = 1400
) {
    companion object {
        fun forSim(perPacketBytes: Int) = OverheadPolicy(perPacketBytes, 1400)
        fun forWifi(perPacketBytes: Int) = OverheadPolicy(perPacketBytes, 1500)
        val RAW = OverheadPolicy(0)
    }
}

data class BilledBytes(
    val rx: Long,
    val tx: Long,
    /** true when the packet count was estimated from the MTU instead of read from the kernel */
    val packetsEstimated: Boolean
)

object OverheadModel {

    /**
     * Applies the framing allowance to a delta.
     *
     * Packet counts are used when the platform exposes them (they are exact for the interface);
     * otherwise the count is estimated from the byte volume and the MTU, which is accurate to
     * one packet per sample — a worst case of a few hundred bytes, and always in the safe
     * direction of over- rather than under-counting.
     */
    fun bill(delta: CounterLedger.Delta, policy: OverheadPolicy): BilledBytes =
        bill(delta.rx, delta.tx, delta.rxPackets, delta.txPackets, policy)

    fun bill(
        rx: Long,
        tx: Long,
        rxPackets: Long,
        txPackets: Long,
        policy: OverheadPolicy
    ): BilledBytes {
        if (policy.perPacketBytes <= 0) return BilledBytes(rx, tx, packetsEstimated = false)
        val mtu = policy.mtu.coerceAtLeast(1).toLong()
        val measured = rxPackets > 0 && txPackets > 0
        val rp = if (rxPackets > 0) rxPackets else ceilDiv(rx, mtu)
        val tp = if (txPackets > 0) txPackets else ceilDiv(tx, mtu)
        return BilledBytes(
            rx = rx + rp * policy.perPacketBytes,
            tx = tx + tp * policy.perPacketBytes,
            packetsEstimated = !measured && (rx > 0 || tx > 0)
        )
    }

    private fun ceilDiv(value: Long, divisor: Long): Long =
        if (value <= 0L) 0L else (value + divisor - 1L) / divisor
}

/** What the platform itself says a subscription or an interface has used. */
data class PlatformUsage(val rx: Long, val tx: Long) {
    val total: Long get() = rx + tx
}

data class Correction(val rx: Long, val tx: Long)

/**
 * The safety net that makes "not a single kilobyte may be missed" true even when the app was
 * killed, throttled or simply not looking.
 *
 * The platform keeps its own per-subscription ledger (the one behind Settings → Data usage). It is
 * cumulative, it counts everything including tethering and system traffic, and it survives our
 * process dying. So whenever the app's own ledger is *lower* than the platform's figure for the
 * same window, the difference is credited to that source — the meter can drift up to the truth,
 * never away from it.
 *
 * A correction is never negative: an app that has counted slightly more (the framing allowance
 * does that on purpose) is not walked back, and the size of the gap stays visible in the UI as
 * the drift figure so the user can see whether the allowance needs tuning.
 */
object ReconciliationPolicy {

    /** Below this the difference is sampling jitter, not missing traffic. */
    const val MIN_MATERIAL_BYTES = 4L * 1024L

    fun correction(ledgerRx: Long, ledgerTx: Long, platform: PlatformUsage): Correction? {
        val dRx = platform.rx - ledgerRx
        val dTx = platform.tx - ledgerTx
        if (dRx < MIN_MATERIAL_BYTES && dTx < MIN_MATERIAL_BYTES) return null
        return Correction(rx = dRx.coerceAtLeast(0L), tx = dTx.coerceAtLeast(0L))
    }

    /**
     * How far the app's ledger sits from the platform's, as a signed byte count.
     * Positive = we are below the platform (traffic not yet credited, will be on the next
     * reconciliation); negative = we counted more, normally the framing allowance.
     */
    fun drift(ledgerRx: Long, ledgerTx: Long, platform: PlatformUsage): Long =
        platform.total - (ledgerRx + ledgerTx)

    /** Share of the platform total that the framing allowance represents, for the UI. */
    fun overheadShare(ledgerTotal: Long, platformTotal: Long): Float =
        if (platformTotal <= 0L) 0f
        else ((ledgerTotal - platformTotal).toDouble() / platformTotal.toDouble() * 100.0).toFloat()
}

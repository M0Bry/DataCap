package com.meter.app.core.ledger

/**
 * One raw reading of a kernel interface counter.
 *
 * [rxPackets]/[txPackets] are -1 when the platform does not expose them, in which case the
 * overhead model falls back to a byte/MTU estimate.
 */
data class InterfaceReading(
    val iface: String,
    val rxBytes: Long,
    val txBytes: Long,
    val rxPackets: Long = -1L,
    val txPackets: Long = -1L
)

/**
 * Which source owns an interface *at this instant*.
 *
 * Ownership is dynamic on purpose. A dual-SIM phone very often exposes a single cellular uplink
 * (`rmnet_data0`) and attaches only the SIM that is currently the default data SIM. The interface
 * therefore does not "belong" to a SIM — it belongs to whichever subscription is carrying data
 * right now, and that is what decides which ledger the bytes go into.
 */
typealias OwnershipMap = Map<String, String>

/**
 * Turns per-interface counter readings into per-source deltas.
 *
 * Three invariants, which together are what "not one kilobyte may be missed" means in practice:
 *
 * 1. **Nothing is counted twice.** Only deltas are credited, and every reading is remembered as
 *    the next baseline — including the very first one, which is baseline-only.
 * 2. **Nothing is lost.** A counter that goes backwards is a driver reset or a 32-bit wrap; both
 *    are recognised and the bytes that really flowed are still credited instead of being clipped
 *    to zero (see [advance]).
 * 3. **Nothing migrates between sources.** When an interface changes owner — the user moves
 *    mobile data from SIM 1 to SIM 2 — the interface is re-based instead of credited, so SIM 1
 *    keeps exactly the bytes it carried and SIM 2 starts counting from that moment. The one
 *    delta that is emitted is empty and flagged [CounterLedger.Delta.rebased], purely so the UI
 *    can show that a handover happened.
 */
class CounterLedger {

    private class Base(var rx: Long, var tx: Long, var rxP: Long, var txP: Long, var owner: String)

    private val bases = LinkedHashMap<String, Base>()

    data class Delta(
        val sourceId: String,
        val iface: String,
        val rx: Long,
        val tx: Long,
        val rxPackets: Long,
        val txPackets: Long,
        val rebased: Boolean = false
    ) {
        val isEmpty: Boolean get() = rx == 0L && tx == 0L
    }

    /**
     * @param readings raw counters, one entry per watched interface
     * @param owners interface → source id; an interface missing from the map is ignored
     *               (loopback, our own tunnel and P2P interfaces must never be metered)
     */
    fun sample(readings: List<InterfaceReading>, owners: OwnershipMap): List<Delta> {
        val out = ArrayList<Delta>(readings.size)
        readings.forEach { reading ->
            val owner = owners[reading.iface] ?: return@forEach
            val base = bases[reading.iface]
            if (base == null) {
                bases[reading.iface] = Base(
                    reading.rxBytes, reading.txBytes, reading.rxPackets, reading.txPackets, owner
                )
                return@forEach
            }
            if (base.owner != owner) {
                // Handover: re-base, never re-credit.
                base.rx = reading.rxBytes
                base.tx = reading.txBytes
                base.rxP = reading.rxPackets
                base.txP = reading.txPackets
                base.owner = owner
                out += Delta(owner, reading.iface, 0L, 0L, 0L, 0L, rebased = true)
                return@forEach
            }
            val dRx = advance(base.rx, reading.rxBytes)
            val dTx = advance(base.tx, reading.txBytes)
            val dRxP = advance(base.rxP.coerceAtLeast(0L), reading.rxPackets.coerceAtLeast(0L))
            val dTxP = advance(base.txP.coerceAtLeast(0L), reading.txPackets.coerceAtLeast(0L))
            base.rx = reading.rxBytes
            base.tx = reading.txBytes
            base.rxP = reading.rxPackets
            base.txP = reading.txPackets
            if (dRx > 0 || dTx > 0) {
                // Packet counts are only useful for the overhead model, and only when both
                // readings are real (>= 0) — otherwise keep the "unknown" marker.
                val rxP = if (reading.rxPackets >= 0 && base.rxP >= 0) dRxP else -1L
                val txP = if (reading.txPackets >= 0 && base.txP >= 0) dTxP else -1L
                out += Delta(owner, reading.iface, dRx, dTx, rxP, txP)
            }
        }
        return out
    }

    /** Forces the next sample to be baseline-only for these interfaces. */
    fun rebase(ifaces: Collection<String>) {
        ifaces.forEach { bases.remove(it) }
    }

    fun clear() = bases.clear()

    /**
     * A compact, persisted form of the baselines. Restoring it after the process was killed means
     * the bytes that flowed while the meter was not running are still credited on the next sample
     * instead of being silently dropped.
     */
    fun export(): List<String> = bases.map { (iface, b) ->
        "$iface|${b.rx}|${b.tx}|${b.rxP}|${b.txP}|${b.owner}"
    }

    fun import(lines: List<String>) {
        bases.clear()
        lines.forEach { line ->
            val parts = line.split('|')
            if (parts.size != 6) return@forEach
            val rx = parts[1].toLongOrNull() ?: return@forEach
            val tx = parts[2].toLongOrNull() ?: return@forEach
            val rxP = parts[3].toLongOrNull() ?: -1L
            val txP = parts[4].toLongOrNull() ?: -1L
            bases[parts[0]] = Base(rx, tx, rxP, txP, parts[5])
        }
    }

    fun baselineOf(iface: String): Long? = bases[iface]?.rx

    companion object {
        /** 2^32 - 1: the largest value a 32-bit driver counter can hold. */
        const val UINT32_MAX = 4_294_967_295L

        /**
         * The lowest reading from which a 32-bit counter can plausibly wrap: three quarters of
         * its range. Below that a drop is far more likely to be a driver reset than a wrap, and
         * in both cases the next reading continues from a small value, so the choice only
         * decides how much of the tail is recovered — never whether bytes are counted twice.
         */
        const val WRAP_FLOOR = 3_221_225_471L   // 75 % of 2^32 - 1

        /**
         * Bytes that flowed between two readings of the same counter, never negative.
         *
         * · normal advance -> the difference;
         * · 32-bit wrap    -> the tail of the old window plus the head of the new one;
         * · hard reset     -> the new value is exactly what has flowed since the reset;
         * · 64-bit counter -> a drop cannot be a wrap, so the new value is the truth.
         */
        fun advance(old: Long, new: Long): Long = when {
            new >= old -> new - old
            old > UINT32_MAX -> new
            old >= WRAP_FLOOR -> (UINT32_MAX - old) + new + 1L
            else -> new
        }
    }
}

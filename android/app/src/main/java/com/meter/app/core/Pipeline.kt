package com.meter.app.core

import com.meter.app.core.ledger.CounterLedger
import com.meter.app.core.ledger.InterfaceReading
import com.meter.app.core.ledger.OverheadModel
import com.meter.app.core.ledger.OverheadPolicy
import com.meter.app.core.ledger.OwnershipMap
import com.meter.app.core.ledger.PlatformUsage
import com.meter.app.core.ledger.ReconciliationPolicy

/**
 * The whole accounting pipeline, free of Android so it can be driven by tests.
 *
 * One [tick] is one sample of the radios:
 *
 *   raw counter readings
 *        └─ CounterLedger          → per-source deltas, handovers re-based, resets recovered
 *             └─ OverheadModel     → adds the framing the operator bills on top of IP
 *                  └─ QuotaEngine  → thresholds (once per cycle) and per-source blocking
 *
 * and [reconcile] is the safety net that pulls any traffic the app failed to see out of the
 * platform's own per-subscription ledger.
 *
 * Nothing in here knows about notifications or Wi-Fi toggles: it hands back effects and the
 * service layer performs them, which keeps the rules testable.
 */
class Pipeline(private val ledger: CounterLedger = CounterLedger()) {

    data class Outcome(
        val usages: Map<String, SourceUsage>,
        val effects: List<QuotaEngine.Effect>,
        /** bytes credited in this tick, per source (after the framing allowance) */
        val credited: Map<String, Long>,
        /** interfaces that changed owner in this tick */
        val handovers: List<String>
    )

    fun tick(
        now: Long,
        readings: List<InterfaceReading>,
        owners: OwnershipMap,
        sources: Map<String, Source>,
        configs: Map<String, SourceConfig>,
        usages: Map<String, SourceUsage>
    ): Outcome {
        val deltas = ledger.sample(readings, owners)
        val next = usages.toMutableMap()

        // A source that has not carried a byte yet still exists, so it gets a running cycle.
        // Without this the Meter page would have nothing to show for an idle SIM or a Wi-Fi
        // network the phone just joined.
        sources.values.forEach { source ->
            if (next[source.id] == null) next[source.id] = SourceUsage(source.id, cycleStart = now)
        }
        val effects = ArrayList<QuotaEngine.Effect>(4)
        val credited = HashMap<String, Long>(4)
        val handovers = ArrayList<String>(1)

        deltas.forEach { delta ->
            val source = sources[delta.sourceId] ?: return@forEach
            val config = configs[delta.sourceId] ?: SourceConfig(delta.sourceId)
            val previous = next[delta.sourceId] ?: SourceUsage(delta.sourceId, cycleStart = now)

            if (delta.rebased) {
                handovers += delta.iface
                // The cycle may still have rolled over while the interface was idle.
                QuotaEngine.rolloverIfDue(config, previous, now)?.let { next[delta.sourceId] = it }
                return@forEach
            }

            // The cycle boundary is applied before the delta, so bytes that arrive after the
            // rollover belong to the new cycle instead of the old one.
            val rolled = QuotaEngine.rolloverIfDue(config, previous, now) ?: previous
            val billed = OverheadModel.bill(delta, policyFor(source, config))
            val grew = rolled.copy(rx = rolled.rx + billed.rx, tx = rolled.tx + billed.tx)

            val wasBlocked = previous.overQuota && config.blockAtQuota
            val result = QuotaEngine.evaluate(source, config, grew, previouslyBlocked = wasBlocked)
            next[delta.sourceId] = result.usage
            effects += result.effects
            credited[delta.sourceId] = (credited[delta.sourceId] ?: 0L) + billed.rx + billed.tx
        }

        // Sources with no traffic still need their cycle checked: an idle SIM rolls over too.
        sources.values.forEach { source ->
            val config = configs[source.id] ?: return@forEach
            val previous = next[source.id] ?: return@forEach
            val rolled = QuotaEngine.rolloverIfDue(config, previous, now) ?: return@forEach
            next[source.id] = rolled
            if (rolled.blocked != previous.blocked && !rolled.blocked) {
                effects += releaseEffect(source)
            }
        }
        return Outcome(next, effects, credited, handovers)
    }

    /**
     * Folds the platform's own figure for a source into our ledger.
     *
     * @return the corrected usage, and the number of bytes that were missing (0 when the two
     *         ledgers already agreed).
     */
    data class ReconciliationOutcome(
        val usage: SourceUsage,
        val addedBytes: Long,
        val effects: List<QuotaEngine.Effect>
    )

    fun reconcile(
        now: Long,
        source: Source,
        config: SourceConfig,
        usage: SourceUsage,
        platform: PlatformUsage?
    ): ReconciliationOutcome {
        if (platform == null) {
            return ReconciliationOutcome(usage.copy(lastReconciledAt = now), 0L, emptyList())
        }
        val drift = ReconciliationPolicy.drift(usage.rx, usage.tx, platform)
        val correction = ReconciliationPolicy.correction(usage.rx, usage.tx, platform)
            ?: return ReconciliationOutcome(
                usage.copy(lastReconciledAt = now, platformTotal = platform.total, drift = drift),
                0L, emptyList()
            )

        val correctedRx = usage.rx + correction.rx
        val correctedTx = usage.tx + correction.tx
        val grown = usage.copy(
            rx = correctedRx,
            tx = correctedTx,
            reconciledBytes = usage.reconciledBytes + correction.rx + correction.tx,
            lastReconciledAt = now,
            platformTotal = platform.total,
            // What is left over after the correction: 0 means both ledgers now agree.
            drift = ReconciliationPolicy.drift(correctedRx, correctedTx, platform)
        )
        // A correction can push a source over its quota, so the rules run again immediately.
        val result = QuotaEngine.evaluate(
            source, config, grown, previouslyBlocked = usage.overQuota && config.blockAtQuota
        )
        return ReconciliationOutcome(
            result.usage, correction.rx + correction.tx,
            result.effects.filter { it !is QuotaEngine.Effect.Notify }
        )
    }

    /** The framing allowance that applies to a source, from its configuration. */
    fun policyFor(source: Source, config: SourceConfig): OverheadPolicy = when (source.type) {
        SourceType.SIM -> OverheadPolicy.forSim(config.overheadBytes)
        SourceType.WIFI -> OverheadPolicy.forWifi(config.overheadBytes)
    }

    fun exportBaselines(): List<String> = ledger.export()

    fun importBaselines(lines: List<String>) {
        if (lines.isNotEmpty()) ledger.import(lines)
    }

    fun forgetBaselines() = ledger.clear()

    private fun releaseEffect(source: Source): QuotaEngine.Effect = when (source.type) {
        SourceType.WIFI -> QuotaEngine.Effect.EnableWifi(source.id)
        SourceType.SIM -> QuotaEngine.Effect.ReleaseSim(source.id)
    }
}

package com.meter.app.core

/**
 * The quota rules, as pure functions. No Android dependency at all, so the behaviour that
 * matters — thresholds, cycle rollover, per-source blocking — can be unit tested on the JVM.
 *
 * Rules, exactly as specified:
 *  1. every source is evaluated on its own counters, quota and cycle;
 *  2. a threshold fires once per cycle, at the first sample where usage crosses it;
 *  3. reaching 100 % (or the user's manual block) blocks *that* source and nothing else;
 *  4. the block is released when the cycle rolls over or the user resets/unblocks the source.
 */
object QuotaEngine {

    /** Side effect the caller (service/engine) has to carry out. */
    sealed interface Effect {
        data class Notify(val sourceId: String, val percent: Int, val blocked: Boolean) : Effect
        /** Wi-Fi source the OS can really cut: disable the network + disconnect. */
        data class DisableWifi(val sourceId: String) : Effect
        /** Wi-Fi source that is allowed again. */
        data class EnableWifi(val sourceId: String) : Effect
        /** SIM source whose quota is spent while it is the active data path. */
        data class HoldSim(val sourceId: String) : Effect
        data class ReleaseSim(val sourceId: String) : Effect
    }

    data class Result(val usage: SourceUsage, val effects: List<Effect>)

    /**
     * Evaluate one source after its counters changed.
     *
     * @param previouslyBlocked whether the caller had already acted on a block for this source,
     *        so effects are emitted only on transitions.
     */
    fun evaluate(
        source: Source,
        config: SourceConfig,
        usage: SourceUsage,
        previouslyBlocked: Boolean = false
    ): Result {
        val effects = ArrayList<Effect>(2)
        val total = usage.total
        val quota = config.quotaBytes

        // A manual block is state only; its effect is emitted by whoever toggled it.
        var usageOut = usage

        var over = false
        if (quota > 0L) {
            over = total >= quota
            val percent = ((total.toDouble() / quota.toDouble()) * 100.0).toInt()
            if (percent > 0) {
                val fired = usage.firedAlerts.toMutableSet()
                config.thresholds.sorted().forEach { threshold ->
                    if (percent >= threshold && !fired.contains(threshold)) {
                        fired += threshold
                        effects += Effect.Notify(
                            sourceId = source.id,
                            percent = threshold,
                            blocked = threshold >= 100 && config.blockAtQuota
                        )
                    }
                }
                if (fired != usage.firedAlerts) usageOut = usageOut.copy(firedAlerts = fired)
            }
        }
        val alertOnly = !config.blockAtQuota
        if (usageOut.overQuota != over || usageOut.alertOnly != alertOnly) {
            usageOut = usageOut.copy(overQuota = over, alertOnly = alertOnly)
        }

        // Block / release transitions, decided by this source's own policy.
        val shouldBlock = over && config.blockAtQuota
        if (shouldBlock && !previouslyBlocked) {
            effects += when (source.type) {
                SourceType.WIFI -> Effect.DisableWifi(source.id)
                SourceType.SIM -> Effect.HoldSim(source.id)
            }
        }
        if (!shouldBlock && previouslyBlocked) {
            effects += when (source.type) {
                SourceType.WIFI -> Effect.EnableWifi(source.id)
                SourceType.SIM -> Effect.ReleaseSim(source.id)
            }
        }
        return Result(usageOut, effects)
    }

    /** Counts packets for the overhead model: bytes / MTU, rounded up. */
    fun estimatePackets(bytes: Long, mtu: Int = 1400): Long =
        if (bytes <= 0L) 0L else (bytes + mtu - 1) / mtu

    /**
     * Rolls a source over when its cycle has ended.
     *
     * @return the new usage, or null when nothing had to change.
     */
    fun rolloverIfDue(config: SourceConfig, usage: SourceUsage, now: Long): SourceUsage? {
        if (usage.cycleStart <= 0L) return usage.copy(cycleStart = now)
        val end = usage.cycleStart + config.periodDays * Units.DAY
        if (now < end) return null
        // Jump forward whole cycles so a phone that was off for a month lands on the right one.
        var start = usage.cycleStart
        while (now >= start + config.periodDays * Units.DAY) start += config.periodDays * Units.DAY
        return usage.copy(
            rx = 0L, tx = 0L, cycleStart = start,
            firedAlerts = emptySet(), overQuota = false, manualBlock = false
        )
    }

    fun percent(usage: SourceUsage, config: SourceConfig): Float =
        if (config.quotaBytes <= 0L) 0f
        else ((usage.total.toDouble() / config.quotaBytes.toDouble()) * 100.0).toFloat()

    fun cycleEnd(usage: SourceUsage, config: SourceConfig): Long =
        if (usage.cycleStart <= 0L) 0L else usage.cycleStart + config.periodDays * Units.DAY

    /** Aggregates a source list into the numbers the Meter page renders. */
    fun scope(
        ids: List<String>,
        label: String,
        sources: Map<String, Source>,
        configs: Map<String, SourceConfig>,
        usages: Map<String, SourceUsage>,
        now: Long
    ): ScopedUsage {
        val aggregate = ids.size != 1
        var used = 0L; var rx = 0L; var tx = 0L; var quota = 0L
        var start = Long.MAX_VALUE
        var period = 30
        @Suppress("UNUSED_VARIABLE")
        var unit = UnitMode.AUTO
        var blocked = ids.isNotEmpty()
        val thresholds = sortedSetOf<Int>()

        ids.forEach { id ->
            val u = usages[id] ?: SourceUsage(id)
            val c = configs[id] ?: SourceConfig(id)
            rx += u.rx; tx += u.tx; used += u.total
            quota += c.quotaBytes
            start = minOf(start, if (u.cycleStart > 0L) u.cycleStart else now)
            thresholds += c.thresholds
            if (!aggregate) {
                period = c.periodDays
                unit = c.unit
                blocked = u.blocked
            } else {
                blocked = blocked && u.blocked
                period = maxOf(period, c.periodDays)
            }
        }
        if (start == Long.MAX_VALUE) start = now
        val left = if (quota > 0L) (quota - used).coerceAtLeast(0L) else 0L
        val percent = if (quota > 0L) (used.toDouble() / quota.toDouble() * 100.0).toFloat() else 0f
        val days = Units.daysBetween(start, now)
        return ScopedUsage(
            ids = ids, label = label, used = used, rx = rx, tx = tx, quota = quota, left = left,
            percent = percent, cycleStart = start, cycleEnd = start + period * Units.DAY,
            periodDays = period, unit = unit, thresholds = thresholds.toList(),
            blocked = blocked, dailyAverage = (used / days).toLong()
        )
    }
}

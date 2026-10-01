package com.meter.app.core

/**
 * Every list and menu in the app is driven by these pure functions, so their behaviour is
 * covered by unit tests instead of being re-implemented inside composables.
 *
 * The lists in question are:
 *  · the All / SIM / Wi-Fi filter pills;
 *  · the chip rows that appear when a family holds more than one source;
 *  · the notification-threshold list (add / edit / delete);
 *  · the quota presets and the quota stepper;
 *  · the sample-interval options.
 */
object Selection {

    /** What the pills show for a family. Order is stable: SIMs first, then Wi-Fi. */
    fun visible(sources: List<Source>, filter: FamilyFilter): List<Source> = when (filter) {
        FamilyFilter.ALL -> sources
        FamilyFilter.SIM -> sources.filter { it.type == SourceType.SIM }
        FamilyFilter.WIFI -> sources.filter { it.type == SourceType.WIFI }
    }

    fun visible(snapshot: MeterSnapshot): List<Source> = visible(snapshot.sources, snapshot.filter)

    /**
     * The ids the Meter page renders.
     *
     * "All" is deliberately the only aggregate: it sums the visible family, because that is what
     * the design's All pill means. A specific family always resolves to exactly one source —
     * never a blend of that family's members — so SIM 1 is never mixed with SIM 2 and one Wi-Fi
     * network is never mixed with another.
     */
    fun selectedIds(snapshot: MeterSnapshot, filter: FamilyFilter = snapshot.filter): List<String> {
        val list = visible(snapshot.sources, filter)
        if (filter == FamilyFilter.ALL) return list.map { it.id }

        val type = filter.type ?: return emptyList()
        val remembered = snapshot.selected[type]
        val chosen = list.firstOrNull { it.id == remembered } ?: list.firstOrNull()
        return if (chosen == null) emptyList() else listOf(chosen.id)
    }

    /** Which source a tap on a chip selects — and it is remembered per family. */
    fun afterSourceTap(snapshot: MeterSnapshot, id: String): Map<SourceType, String> {
        val source = snapshot.sources.firstOrNull { it.id == id } ?: return snapshot.selected
        return snapshot.selected.toMutableMap().apply { put(source.type, id) }
    }

    /**
     * Tapping a pill must always land on a usable selection: if the family was never opened, or
     * the remembered source is gone (a SIM removed, a Wi-Fi network forgotten), the first member
     * of that family is selected instead of leaving the page empty.
     */
    fun afterFilterTap(snapshot: MeterSnapshot, filter: FamilyFilter): SelectionResult {
        val list = visible(snapshot.sources, filter)
        val selected = snapshot.selected.toMutableMap()
        val type = filter.type
        if (type != null) {
            val remembered = selected[type]
            if (list.none { it.id == remembered }) {
                list.firstOrNull()?.let { selected[type] = it.id }
            }
        }
        return SelectionResult(filter, selected, emptyFamily = filter != FamilyFilter.ALL && list.isEmpty())
    }

    data class SelectionResult(
        val filter: FamilyFilter,
        val selected: Map<SourceType, String>,
        val emptyFamily: Boolean
    )

    /**
     * A source list is a *live* list: SIMs are pulled, Wi-Fi networks come and go, and the user
     * changes which SIM carries data. This decides what survives:
     *  · a source that disappeared keeps its config and counters, but drops out of the UI;
     *  · new sources get a config and a running cycle;
     *  · the remembered selection is repaired if it pointed at something that is gone.
     */
    fun reconcileSources(
        previous: MeterSnapshot,
        discovered: List<Source>,
        now: Long,
        defaultConfig: (Source) -> SourceConfig
    ): ReconcileResult {
        val configs = previous.configs.toMutableMap()
        val usages = previous.usage.toMutableMap()
        discovered.forEach { source ->
            if (configs[source.id] == null) configs[source.id] = defaultConfig(source)
            val existing = usages[source.id]
            usages[source.id] = when {
                existing == null -> SourceUsage(source.id, cycleStart = now)
                existing.cycleStart <= 0L -> existing.copy(cycleStart = now)
                else -> existing
            }
        }
        val selected = previous.selected.toMutableMap()
        SourceType.values().forEach { type ->
            val family = discovered.filter { it.type == type }
            val remembered = selected[type]
            if (family.isNotEmpty() && family.none { it.id == remembered }) {
                selected[type] = family.first().id
            }
        }
        return ReconcileResult(discovered, configs, usages, previous.filter, selected)
    }

    data class ReconcileResult(
        val sources: List<Source>,
        val configs: Map<String, SourceConfig>,
        val usage: Map<String, SourceUsage>,
        val filter: FamilyFilter,
        val selected: Map<SourceType, String>
    )
}

/**
 * The notification-threshold list: add, edit, delete, and the normalisation that keeps it
 * ordered and unique so two thresholds can never fight over the same alert.
 */
object ThresholdList {

    const val MAX = 12

    fun normalize(list: List<Int>): List<Int> =
        list.filter { it in 1..100 }.distinct().sorted().take(MAX)

    /** @return the new list, or null when the list is full or nothing is left to add */
    fun add(list: List<Int>, value: Int? = null): List<Int>? {
        val current = normalize(list)
        if (current.size >= MAX) return null
        val candidate = value ?: suggestion(current) ?: return null
        val next = normalize(current + candidate)
        return if (next.size == current.size) null else next
    }

    fun update(list: List<Int>, index: Int, value: Int): List<Int> {
        val current = normalize(list).toMutableList()
        if (index !in current.indices) return current
        current[index] = value.coerceIn(1, 100)
        return normalize(current)
    }

    fun remove(list: List<Int>, index: Int): List<Int> {
        val current = normalize(list).toMutableList()
        if (index in current.indices) current.removeAt(index)
        return current
    }

    fun suggestion(list: List<Int>): Int? =
        SUGGESTIONS.firstOrNull { it !in normalize(list) }

    val SUGGESTIONS = listOf(10, 20, 25, 30, 40, 50, 60, 70, 75, 80, 90, 95, 100)

    val DEFAULT = listOf(50, 75, 100)
}

/** Quota presets and the +/- stepper, shared by the Setup page and its tests. */
object QuotaSteps {

    val PRESETS: List<Pair<String, Long>> = listOf(
        "500 MB" to 500L * Units.MIB,
        "1 GB" to 1L * Units.GIB,
        "3 GB" to 3L * Units.GIB,
        "5 GB" to 5L * Units.GIB,
        "20 GB" to 20L * Units.GIB,
        "50 GB" to 50L * Units.GIB,
        "Unlimited" to 0L
    )

    /** 100 MB steps under a gigabyte, then half-gigabyte and gigabyte steps. */
    fun step(current: Long, direction: Int): Long {
        val size = when {
            current >= 10L * Units.GIB -> 1L * Units.GIB
            current >= 1L * Units.GIB -> 500L * Units.MIB
            else -> 100L * Units.MIB
        }
        val next = (current + direction * size).coerceAtLeast(0L)
        // Snap to the grid of the step in use — 100 MB / 500 MB / 1 GB — so stepping away from a
        // preset and back lands exactly on it again instead of a near miss.
        return (next / size) * size
    }

    /** A preset is "on" when the quota matches it exactly (Unlimited is the 0 case). */
    fun isActive(quotaBytes: Long, preset: Pair<String, Long>): Boolean = quotaBytes == preset.second
}

/** Sample-interval options, with the range the engine enforces. */
object SampleIntervals {

    const val MIN = 2_000L
    const val MAX = 60_000L
    const val DEFAULT = 5_000L

    val OPTIONS: List<Pair<String, Long>> = listOf(
        "2 s" to 2_000L,
        "5 s" to 5_000L,
        "10 s" to 10_000L,
        "30 s" to 30_000L
    )

    fun sanitize(value: Long): Long = value.coerceIn(MIN, MAX)

    fun labelOf(value: Long): String =
        OPTIONS.firstOrNull { it.second == sanitize(value) }?.first ?: "${sanitize(value) / 1000} s"
}

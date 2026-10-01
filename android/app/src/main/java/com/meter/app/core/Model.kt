package com.meter.app.core

/**
 * Everything that is watched and quota-managed is a [Source] — never a single global total.
 *
 * Two families exist, and they are handled symmetrically:
 *   · SIM   — one entry per SIM/subscription ("SIM 1 · Vodafone", "SIM 2 · Orange")
 *   · WIFI  — one entry per SSID ever seen ("Home Wi-Fi", "Office")
 *
 * The [id] is what persists in storage, so it is derived only from stable facts:
 *   sim  -> "sim:<subscriptionId or slot>"
 *   wifi -> "wifi:<SSID>"
 */
data class Source(
    val id: String,
    val type: SourceType,
    val name: String,          // display name: carrier name or SSID
    val detail: String,        // "SIM 1", "5 GHz", …
    val iface: String,         // kernel interface: rmnet_data0 / ccmni0 / wlan0 / …
    val subId: Int = -1,       // SIMs only
    val slot: Int = -1,        // SIMs only
    val carrier: String = "",
    /** Per-SIM reconciliation key; empty when telephony permission was denied. */
    val subscriberId: String = ""
) {
    val isSim get() = type == SourceType.SIM
}

enum class SourceType { SIM, WIFI }

/** What the user configured for a source. Persisted per source id. */
data class SourceConfig(
    val id: String,
    val quotaBytes: Long = 0L,        // 0 = unlimited
    val periodDays: Int = 30,         // length of one usage cycle
    val unit: UnitMode = UnitMode.AUTO,
    val thresholds: List<Int> = listOf(50, 75, 100),
    val blockAtQuota: Boolean = true,
    /** Extra bytes added per packet so the meter matches what the operator bills. */
    val overheadBytes: Int = 36
)

/** What the app measured for a source inside the current cycle. */
data class SourceUsage(
    val id: String,
    val rx: Long = 0L,
    val tx: Long = 0L,
    val cycleStart: Long = 0L,
    /** thresholds already announced in this cycle, e.g. {50, 75} */
    val firedAlerts: Set<Int> = emptySet(),
    /** user pressed "Block this source" in Setup */
    val manualBlock: Boolean = false,
    /** set by the engine when the quota is spent (derived, cached for the UI) */
    val overQuota: Boolean = false,
    /** true when this source is set to alert-only in Setup, so 100 % must not cut it off */
    val alertOnly: Boolean = false,
    /** bytes the platform ledger had that our own counters had missed (never lost) */
    val reconciledBytes: Long = 0L,
    val lastReconciledAt: Long = 0L,
    /** the platform's own total for this cycle, used by the Sanity Check card */
    val platformTotal: Long = 0L,
    /** platform total minus our total: positive = we are still catching up */
    val drift: Long = 0L
) {
    val total get() = rx + tx

    val blocked get() = manualBlock || (overQuota && !alertOnly)
}

enum class UnitMode { AUTO, MB, GB }

/**
 * A snapshot of everything the UI needs. Emitted by [com.meter.app.state.MeterRepository]
 * whenever a counter changes, so Compose never has to recompute accounting itself.
 */
data class MeterSnapshot(
    val sources: List<Source> = emptyList(),
    val configs: Map<String, SourceConfig> = emptyMap(),
    val usage: Map<String, SourceUsage> = emptyMap(),
    val filter: FamilyFilter = FamilyFilter.ALL,
    val selected: Map<SourceType, String> = emptyMap(),
    /** currently active default connection, as far as the OS tells us */
    val liveSourceId: String? = null,
    val downBytesPerSec: Long = 0L,
    val upBytesPerSec: Long = 0L,
    val meteringPaused: Boolean = false,
    val serviceRunning: Boolean = false,
    /** Usage access granted: the platform's per-SIM ledger can be read and folded in */
    val usageAccessGranted: Boolean = false,
    /** true when the phone exposes one uplink interface per SIM (exact kernel split) */
    val perSimInterfaces: Boolean = false,
    val killSwitchEnabled: Boolean = true,
    val sampleMillis: Long = 5_000L,
    val vpnPermissionGranted: Boolean = false,
    val killSwitchActive: Boolean = false,
    val locationGranted: Boolean = false,
    val phoneStateGranted: Boolean = false
) {
    fun configOf(id: String) = configs[id] ?: SourceConfig(id)
    fun usageOf(id: String) = usage[id] ?: SourceUsage(id)
    fun sourceOf(id: String?) = sources.firstOrNull { it.id == id }

    /** Sources visible for the current filter (All / SIM / Wi-Fi). */
    val visible: List<Source>
        get() = when (filter) {
            FamilyFilter.ALL -> sources
            FamilyFilter.SIM -> sources.filter { it.type == SourceType.SIM }
            FamilyFilter.WIFI -> sources.filter { it.type == SourceType.WIFI }
        }

    /**
     * The source (or family) the Meter page is currently showing — always exactly one source
     * when a family is selected, so SIM 1 is never blended with SIM 2. The rule lives in
     * [Selection] so it is covered by unit tests instead of being duplicated here.
     */
    val selectedIds: List<String> get() = Selection.selectedIds(this)

    val isAggregate get() = filter == FamilyFilter.ALL

    val selectedLabel: String
        get() = when (filter) {
            FamilyFilter.ALL -> "All sources"
            FamilyFilter.SIM -> selectedIds.firstOrNull()?.let { s -> sourceOf(s)?.name } ?: "SIM"
            FamilyFilter.WIFI -> selectedIds.firstOrNull()?.let { s -> sourceOf(s)?.name } ?: "Wi-Fi"
        }
}

enum class FamilyFilter(val type: SourceType?) {
    ALL(null), SIM(SourceType.SIM), WIFI(SourceType.WIFI)
}

/** Aggregate view of one source or of a whole family — what the Meter renders. */
data class ScopedUsage(
    val ids: List<String>,
    val label: String,
    val used: Long,
    val rx: Long,
    val tx: Long,
    val quota: Long,
    val left: Long,
    val percent: Float,
    val cycleStart: Long,
    val cycleEnd: Long,
    val periodDays: Int,
    val unit: UnitMode,
    val thresholds: List<Int>,
    val blocked: Boolean,
    val dailyAverage: Long
)

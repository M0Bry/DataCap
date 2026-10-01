package com.meter.app.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Storage for [SourceConfig] and [SourceUsage].
 *
 * Deliberately dependency-free (SharedPreferences + JSON) and small: the meter writes a
 * handful of integers and longs every few seconds, and must never block the sampler.
 * JSON keeps the file human-inspectable and forward-compatible if fields are added.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("meter.store", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ config

    fun loadConfigs(): Map<String, SourceConfig> {
        val raw = sp.getString(KEY_CONFIGS, null) ?: return emptyMap()
        return try {
            val out = LinkedHashMap<String, SourceConfig>()
            val obj = JSONObject(raw)
            for (key in obj.keys()) {
                val o = obj.getJSONObject(key)
                out[key] = SourceConfig(
                    id = key,
                    quotaBytes = o.optLong("quota", 0L),
                    periodDays = o.optInt("period", 30),
                    unit = runCatching { UnitMode.valueOf(o.optString("unit", "AUTO")) }
                        .getOrDefault(UnitMode.AUTO),
                    thresholds = o.optJSONArray("thr")?.toIntList()
                        ?: listOf(50, 75, 100),
                    blockAtQuota = o.optBoolean("block", true),
                    overheadBytes = o.optInt("overhead", 36)
                )
            }
            out
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    fun saveConfigs(configs: Map<String, SourceConfig>) {
        val obj = JSONObject()
        configs.forEach { (id, c) ->
            obj.put(id, JSONObject().apply {
                put("quota", c.quotaBytes)
                put("period", c.periodDays)
                put("unit", c.unit.name)
                put("thr", JSONArray(c.thresholds))
                put("block", c.blockAtQuota)
                put("overhead", c.overheadBytes)
            })
        }
        sp.edit().putString(KEY_CONFIGS, obj.toString()).apply()
    }

    // ------------------------------------------------------------------- usage

    fun loadUsage(): Map<String, SourceUsage> {
        val raw = sp.getString(KEY_USAGE, null) ?: return emptyMap()
        return try {
            val out = LinkedHashMap<String, SourceUsage>()
            val obj = JSONObject(raw)
            for (key in obj.keys()) {
                val o = obj.getJSONObject(key)
                out[key] = SourceUsage(
                    id = key,
                    rx = o.optLong("rx", 0L),
                    tx = o.optLong("tx", 0L),
                    cycleStart = o.optLong("start", 0L),
                    firedAlerts = o.optJSONArray("alerts")?.toIntList()?.toSet() ?: emptySet(),
                    manualBlock = o.optBoolean("manualBlock", false),
                    overQuota = o.optBoolean("over", false),
                    reconciledBytes = o.optLong("reconciled", 0L),
                    lastReconciledAt = o.optLong("reconciledAt", 0L),
                    platformTotal = o.optLong("platform", 0L),
                    drift = o.optLong("drift", 0L)
                )
            }
            out
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    fun saveUsage(usage: Map<String, SourceUsage>) {
        val obj = JSONObject()
        usage.forEach { (id, u) ->
            obj.put(id, JSONObject().apply {
                put("rx", u.rx)
                put("tx", u.tx)
                put("start", u.cycleStart)
                put("alerts", JSONArray(u.firedAlerts.toList().sorted()))
                put("manualBlock", u.manualBlock)
                put("over", u.overQuota)
                put("reconciled", u.reconciledBytes)
                put("reconciledAt", u.lastReconciledAt)
                put("platform", u.platformTotal)
                put("drift", u.drift)
            })
        }
        sp.edit().putString(KEY_USAGE, obj.toString()).apply()
    }

    /**
     * The counter baselines of the last sample, one line per interface:
     * `iface|rx|tx|rxPackets|txPackets|ownerSourceId`.
     *
     * Persisting them is what makes the meter lossless across a process kill or a reboot: the
     * next sample diffs against the stored values instead of re-baselining, so the bytes that
     * flowed while the app was not running are still credited to the right source.
     */
    fun saveLedger(lines: List<String>) {
        sp.edit().putString(KEY_LEDGER, JSONArray(lines).toString()).apply()
    }

    fun loadLedger(): List<String> {
        val raw = sp.getString(KEY_LEDGER, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it, null) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * Wi-Fi networks the device has been seen on, as `id|name|iface|detail`. Keeping them means a
     * network's quota, cycle and counters survive leaving the network and coming back.
     */
    fun saveKnownWifi(lines: List<String>) {
        sp.edit().putString(KEY_KNOWN_WIFI, JSONArray(lines).toString()).apply()
    }

    fun loadKnownWifi(): List<String> {
        val raw = sp.getString(KEY_KNOWN_WIFI, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it, null) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    // ------------------------------------------------------------- app settings

    var filter: String
        get() = sp.getString(KEY_FILTER, FamilyFilter.ALL.name) ?: FamilyFilter.ALL.name
        set(v) = sp.edit().putString(KEY_FILTER, v).apply()

    fun selectedFor(type: SourceType): String? = sp.getString(KEY_SELECTED + type.name, null)

    fun setSelected(type: SourceType, id: String) =
        sp.edit().putString(KEY_SELECTED + type.name, id).apply()

    var meteringPaused: Boolean
        get() = sp.getBoolean(KEY_PAUSED, false)
        set(v) = sp.edit().putBoolean(KEY_PAUSED, v).apply()

    var sampleMillis: Long
        get() = sp.getLong(KEY_INTERVAL, 5_000L).coerceIn(2_000L, 60_000L)
        set(v) = sp.edit().putLong(KEY_INTERVAL, v.coerceIn(2_000L, 60_000L)).apply()

    var killSwitchEnabled: Boolean
        get() = sp.getBoolean(KEY_KILL_SWITCH, true)
        set(v) = sp.edit().putBoolean(KEY_KILL_SWITCH, v).apply()

    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(KEY_ONBOARDED, v).apply()

    /** Enum helpers that never throw on a corrupt value. */
    fun filterEnum(): FamilyFilter =
        runCatching { FamilyFilter.valueOf(filter) }.getOrDefault(FamilyFilter.ALL)

    private fun JSONArray.toIntList(): List<Int> =
        (0 until length()).map { optInt(it, 0) }.filter { it in 1..100 }.sorted()

    private companion object {
        const val KEY_CONFIGS = "configs"
        const val KEY_USAGE = "usage"
        const val KEY_LEDGER = "ledgerBaselines"
        const val KEY_KNOWN_WIFI = "knownWifi"
        const val KEY_FILTER = "filter"
        const val KEY_SELECTED = "selected."
        const val KEY_PAUSED = "paused"
        const val KEY_INTERVAL = "interval"
        const val KEY_KILL_SWITCH = "killSwitch"
        const val KEY_ONBOARDED = "onboarded"
    }
}

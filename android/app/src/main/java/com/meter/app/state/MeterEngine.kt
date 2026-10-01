package com.meter.app.state

import android.content.Context
import android.util.Log
import com.meter.app.core.FamilyFilter
import com.meter.app.core.MeterSnapshot
import com.meter.app.core.Pipeline
import com.meter.app.core.QuotaEngine
import com.meter.app.core.SampleIntervals
import com.meter.app.core.Selection
import com.meter.app.core.Source
import com.meter.app.core.SourceConfig
import com.meter.app.core.SourceRegistry
import com.meter.app.core.SourceType
import com.meter.app.core.SourceUsage
import com.meter.app.core.ThresholdList
import com.meter.app.core.UnitMode
import com.meter.app.core.Units
import com.meter.app.core.Prefs
import com.meter.app.core.platform.InterfaceCounters
import com.meter.app.core.platform.Networks
import com.meter.app.core.platform.PlatformStats
import com.meter.app.service.Enforcement
import com.meter.app.service.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * The single owner of all accounting state in the process.
 *
 * One sample is: read the kernel counters of every uplink → resolve which source owns each
 * interface right now → let [Pipeline] turn that into per-source deltas, thresholds and blocks →
 * publish. Every few samples the platform's own per-subscription ledger is folded in so traffic
 * the app could not see is still credited, and never lost.
 */
object MeterEngine {

    private const val TAG = "Meter.Engine"
    private const val SAVE_EVERY_N_SAMPLES = 6
    private const val RECONCILE_EVERY_N_SAMPLES = 12
    private const val OWNERSHIP_TTL_MILLIS = 10_000L

    private lateinit var appContext: Context
    private lateinit var prefs: Prefs
    private lateinit var registry: SourceRegistry
    private lateinit var counters: InterfaceCounters
    private lateinit var networks: Networks
    private lateinit var platformStats: PlatformStats
    private lateinit var notifier: Notifier
    private lateinit var enforcement: Enforcement
    private val pipeline = Pipeline()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sampleLock = Mutex()
    private val refCount = AtomicInteger(0)
    private var loopJob: Job? = null

    private var samples = 0
    private var lastSampleAt = 0L
    private var cachedOwners: Map<String, String> = emptyMap()
    private var cachedOwnersAt = 0L
    private val disabledWifi = HashSet<String>()

    private val _state = MutableStateFlow(MeterSnapshot())
    val state: StateFlow<MeterSnapshot> = _state.asStateFlow()

    var initialized = false
        private set

    // ------------------------------------------------------------------ startup

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            prefs = Prefs(appContext)
            registry = SourceRegistry(appContext)
            counters = InterfaceCounters()
            networks = Networks(appContext)
            platformStats = PlatformStats(appContext)
            enforcement = Enforcement(appContext)
            notifier = Notifier(appContext)

            val now = System.currentTimeMillis()
            val sources = registry.discover(prefs.loadKnownWifi())
            val reconciled = Selection.reconcileSources(_state.value, sources, now) { defaultConfigFor(it) }

            // Restore the counter baselines of the previous run: the bytes that flowed while the
            // app was not running are then credited on the first sample instead of being lost.
            pipeline.importBaselines(prefs.loadLedger())

            cachedOwners = registry.ownership(sources)
            cachedOwnersAt = now
            lastSampleAt = now

            _state.value = _state.value.copy(
                sources = reconciled.sources,
                configs = reconciled.configs,
                usage = reconciled.usage,
                filter = prefs.filterEnum(),
                selected = reconciled.selected,
                meteringPaused = prefs.meteringPaused,
                sampleMillis = prefs.sampleMillis,
                killSwitchEnabled = prefs.killSwitchEnabled,
                killSwitchActive = enforcement.isValveEngaged(),
                vpnPermissionGranted = enforcement.vpnPrepared(),
                usageAccessGranted = platformStats.hasUsageAccess(),
                perSimInterfaces = registry.hasPerSimInterfaces(),
                locationGranted = enforcement.hasLocation(),
                phoneStateGranted = enforcement.hasPhoneState(),
                liveSourceId = ownersToLiveSource(cachedOwners, sources)
            )
            initialized = true
        }
    }

    /** A brand-new source gets a sensible default plan: a month, three thresholds, no framing. */
    private fun defaultConfigFor(source: Source): SourceConfig = SourceConfig(
        id = source.id,
        quotaBytes = when (source.type) {
            SourceType.SIM -> 3L * Units.GIB
            SourceType.WIFI -> 0L          // Wi-Fi defaults to unlimited: LAN traffic is free
        },
        periodDays = 30,
        unit = UnitMode.AUTO,
        thresholds = ThresholdList.DEFAULT,
        blockAtQuota = source.type == SourceType.SIM,
        // Modem framing for mobile, 802.11 framing for Wi-Fi: the difference between the kernel's
        // count and what the operator or the access point actually transfers.
        overheadBytes = if (source.type == SourceType.SIM) 36 else 24
    )

    fun startSampling() {
        if (!initialized) return
        if (refCount.incrementAndGet() == 1) {
            loopJob = scope.launch {
                while (refCount.get() > 0) {
                    tickSafely()
                    delay(_state.value.sampleMillis.coerceAtLeast(SampleIntervals.MIN))
                }
            }
        }
    }

    fun stopSampling() {
        if (!initialized) return
        if (refCount.decrementAndGet() <= 0) {
            refCount.set(0)
            loopJob?.cancel()
            loopJob = null
            persist(force = true)
        }
    }

    // -------------------------------------------------------------- the sampler

    private suspend fun tickSafely() {
        try {
            tick()
        } catch (t: Throwable) {
            Log.w(TAG, "sample failed: ${t.message}", t)
        }
    }

    private suspend fun tick() = sampleLock.withLock {
        val now = System.currentTimeMillis()
        val current = _state.value
        if (current.meteringPaused) {
            lastSampleAt = now
            return@withLock
        }
        val elapsed = (now - lastSampleAt).coerceAtLeast(1L)
        lastSampleAt = now

        val owners = ownership(current.sources, now)
        val readings = counters.readings(owners.keys)
        val sourceById = current.sources.associateBy { it.id }

        val outcome = pipeline.tick(
            now = now,
            readings = readings,
            owners = owners,
            sources = sourceById,
            configs = current.configs,
            usages = current.usage
        )

        val usages = outcome.usages.toMutableMap()
        outcome.effects.forEach { effect ->
            when (effect) {
                is QuotaEngine.Effect.Notify -> {
                    val source = sourceById[effect.sourceId] ?: return@forEach
                    val config = current.configOf(effect.sourceId)
                    notifier.threshold(
                        source, effect.percent, usages[effect.sourceId]?.total ?: 0L,
                        config.quotaBytes, effect.blocked, config.unit
                    )
                }
                is QuotaEngine.Effect.DisableWifi -> {
                    val source = sourceById[effect.sourceId] ?: return@forEach
                    if (enforcement.disableWifi(source.name)) disabledWifi += source.id
                    notifier.info(
                        source, "Wi-Fi quota reached",
                        "${source.name} reached its quota and has been disconnected. " +
                            "Mobile data and your other Wi-Fi networks are unaffected."
                    )
                }
                is QuotaEngine.Effect.EnableWifi -> {
                    val source = sourceById[effect.sourceId] ?: return@forEach
                    if (disabledWifi.remove(source.id)) enforcement.enableWifi(source.name)
                    notifier.info(source, "Wi-Fi quota reset", "${source.name} is available again.")
                }
                is QuotaEngine.Effect.HoldSim -> {
                    val source = sourceById[effect.sourceId] ?: return@forEach
                    notifier.info(
                        source, "Mobile data blocked",
                        "${source.name} is out of data. While this SIM is the active connection, " +
                            "data is paused — switch to Wi-Fi or reset the cycle in Meter."
                    )
                }
                is QuotaEngine.Effect.ReleaseSim -> Unit
            }
        }

        // The SIM valve follows the active connection, never the SIM list: engaged only while the
        // exhausted SIM is the one carrying traffic, released as soon as the phone moves on.
        val liveId = liveSourceId(owners, current.sources)
        val activeSource = sourceById[liveId]
        val activeBlockedSim = activeSource?.takeIf {
            it.type == SourceType.SIM && usages[it.id]?.blocked == true
        }
        enforcement.syncKillSwitch(activeBlockedSim)
        outcome.handovers.forEach { iface ->
            Log.i(TAG, "interface $iface changed owner: counters re-based, nothing migrated")
        }

        val elapsedSeconds = (elapsed / 1000.0).coerceAtLeast(0.001)
        val liveCredited = outcome.credited[liveId] ?: 0L

        samples++
        if (samples % RECONCILE_EVERY_N_SAMPLES == 0) {
            reconcile(now, usages, sourceById)
        }
        if (samples % SAVE_EVERY_N_SAMPLES == 0) {
            persist(usages, force = true)
        }

        _state.value = current.copy(
            usage = usages,
            liveSourceId = liveId,
            downBytesPerSec = (liveCredited * 0.9 / elapsedSeconds).toLong(),
            upBytesPerSec = (liveCredited * 0.1 / elapsedSeconds).toLong(),
            perSimInterfaces = registry.hasPerSimInterfaces()
        )
    }

    /**
     * Folds the platform's ledger into ours — the guarantee that nothing is missed.
     *
     * SIMs are reconciled individually against their own subscription (which is what makes the
     * figure trustworthy on a dual-SIM phone), the Wi-Fi family is reconciled as a whole because
     * the platform does not break Wi-Fi down per SSID, and the residual is credited to the SSID
     * that is carrying traffic right now.
     */
    private fun reconcile(
        now: Long,
        usages: MutableMap<String, SourceUsage>,
        sources: Map<String, Source>
    ) {
        if (!platformStats.hasUsageAccess()) return
        val uplinks = registry.uplinks()
        val multiSim = registry.simSlots(uplinks).size > 1
        var changed = false

        sources.values.filter { it.type == SourceType.SIM }.forEach { source ->
            val usage = usages[source.id] ?: return@forEach
            val platform = platformStats.forSource(
                source = source,
                subscriberId = source.subscriberId,
                start = usage.cycleStart,
                end = now,
                // Without telephony permission the platform figure covers all SIMs at once, which
                // is only meaningful when there is a single data SIM.
                singleDataSim = !multiSim
            ) ?: return@forEach
            val result = pipeline.reconcile(
                now, source, _state.value.configOf(source.id), usage, platform
            )
            usages[source.id] = result.usage
            if (result.addedBytes > 0) {
                changed = true
                Log.i(TAG, "reconciled ${result.addedBytes} bytes for ${source.name}")
            }
        }

        val wifiSources = sources.values.filter { it.type == SourceType.WIFI }
        if (wifiSources.isNotEmpty()) {
            val ledgerTotal = wifiSources.sumOf { usages[it.id]?.total ?: 0L }
            val platform = platformStats.wifi(
                start = usages[wifiSources.first().id]?.cycleStart ?: now, end = now
            )
            if (platform != null) {
                val correctionDelta = platform.total - ledgerTotal
                if (correctionDelta >= com.meter.app.core.ledger.ReconciliationPolicy.MIN_MATERIAL_BYTES) {
                    val owner = registry.activeWifiSourceId()?.let { id -> sources[id] }
                        ?: wifiSources.first()
                    val usage = usages[owner.id] ?: SourceUsage(owner.id, cycleStart = now)
                    val half = correctionDelta / 2
                    usages[owner.id] = usage.copy(
                        rx = usage.rx + half,
                        tx = usage.tx + (correctionDelta - half),
                        reconciledBytes = usage.reconciledBytes + correctionDelta,
                        lastReconciledAt = now,
                        platformTotal = platform.total,
                        drift = platform.total - (ledgerTotal + correctionDelta)
                    )
                    changed = true
                    Log.i(TAG, "reconciled $correctionDelta bytes of Wi-Fi into ${owner.name}")
                }
            }
        }
        if (changed) persist(usages, force = false)
    }

    // -------------------------------------------------------------- ownership

    private fun ownership(sources: List<Source>, now: Long): Map<String, String> {
        val fresh = now - cachedOwnersAt < OWNERSHIP_TTL_MILLIS
        if (fresh && cachedOwners.isNotEmpty()) return cachedOwners
        cachedOwners = registry.ownership(sources)
        cachedOwnersAt = now
        return cachedOwners
    }

    private fun liveSourceId(owners: Map<String, String>, sources: List<Source>): String? =
        ownersToLiveSource(owners, sources)

    private fun ownersToLiveSource(owners: Map<String, String>, sources: List<Source>): String? {
        val activeIface = networks.activeInterface() ?: return null
        owners[activeIface]?.let { return it }
        // The active interface was not metered (VPN up, ethernet): fall back to the transport.
        return when (networks.activeTransport()) {
            Networks.Transport.WIFI ->
                registry.activeWifiSourceId() ?: sources.firstOrNull { it.type == SourceType.WIFI }?.id
            Networks.Transport.CELLULAR ->
                sources.firstOrNull { it.type == SourceType.SIM }?.id
            else -> null
        }
    }

    // ------------------------------------------------------------ source changes

    /** Called whenever connectivity or the SIM set may have changed. */
    fun refreshSources() {
        if (!initialized) return
        scope.launch {
            sampleLock.withLock {
                val now = System.currentTimeMillis()
                val current = _state.value
                val discovered = registry.discover(prefs.loadKnownWifi())
                val reconciled = Selection.reconcileSources(current, discovered, now) {
                    defaultConfigFor(it)
                }
                prefs.saveKnownWifi(registry.wifiPersistenceLines(discovered))
                cachedOwners = registry.ownership(discovered)
                cachedOwnersAt = now
                if (cachedOwners.keys != counters.uplinkInterfaces().toSet()) {
                    // A brand-new interface starts baseline-only; one that disappeared keeps its
                    // baseline so its bytes are still credited when it comes back.
                    pipeline.forgetBaselines()
                    pipeline.importBaselines(prefs.loadLedger())
                }
                _state.value = current.copy(
                    sources = reconciled.sources,
                    configs = reconciled.configs,
                    usage = reconciled.usage,
                    selected = reconciled.selected,
                    filter = reconciled.filter,
                    liveSourceId = ownersToLiveSource(cachedOwners, discovered),
                    perSimInterfaces = registry.hasPerSimInterfaces()
                )
            }
        }
    }

    // ------------------------------------------------------------------- actions

    fun setFilter(filter: FamilyFilter) {
        val result = Selection.afterFilterTap(_state.value, filter)
        prefs.filter = filter.name
        _state.value = _state.value.copy(filter = result.filter, selected = result.selected)
    }

    fun select(id: String) {
        val source = _state.value.sourceOf(id) ?: return
        val selected = Selection.afterSourceTap(_state.value, id)
        prefs.setSelected(source.type, id)
        _state.value = _state.value.copy(selected = selected)
    }

    fun updateConfig(id: String, transform: (SourceConfig) -> SourceConfig) {
        val current = _state.value
        val existing = current.configOf(id)
        val next = transform(existing).let { it.copy(thresholds = ThresholdList.normalize(it.thresholds)) }
        val configs = current.configs.toMutableMap().apply { put(id, next) }
        prefs.saveConfigs(configs)
        _state.value = current.copy(configs = configs)
        reEvaluate(id)
    }

    fun setUnitForAll(unit: UnitMode) {
        val current = _state.value
        val configs = current.configs.mapValues { it.value.copy(unit = unit) }
        prefs.saveConfigs(configs)
        _state.value = current.copy(configs = configs)
    }

    /** Reset one source (or a whole family) and start a fresh cycle now. Counters stay based. */
    fun resetCycle(ids: List<String>) {
        val current = _state.value
        val now = System.currentTimeMillis()
        val usages = current.usage.toMutableMap()
        ids.forEach { id ->
            val previous = usages[id] ?: return@forEach
            usages[id] = previous.copy(
                rx = 0L, tx = 0L, cycleStart = now, firedAlerts = emptySet(),
                overQuota = false, manualBlock = false,
                reconciledBytes = 0L, platformTotal = 0L, drift = 0L, lastReconciledAt = 0L
            )
            val source = current.sourceOf(id)
            if (source != null && source.type == SourceType.WIFI && disabledWifi.remove(id)) {
                enforcement.enableWifi(source.name)
            }
        }
        prefs.saveUsage(usages)
        _state.value = current.copy(usage = usages)
    }

    fun setManualBlock(id: String, blocked: Boolean) {
        val current = _state.value
        val usage = current.usageOf(id).copy(manualBlock = blocked)
        val usages = current.usage.toMutableMap().apply { put(id, usage) }
        val source = current.sourceOf(id)
        if (source != null) {
            when {
                blocked && source.type == SourceType.WIFI ->
                    if (enforcement.disableWifi(source.name)) disabledWifi += id
                blocked && source.type == SourceType.SIM -> enforcement.holdSim(source)
                !blocked && source.type == SourceType.WIFI ->
                    if (disabledWifi.remove(id)) enforcement.enableWifi(source.name)
                !blocked && source.type == SourceType.SIM -> {
                    val activeBlockedSim = _state.value.sources.firstOrNull {
                        it.type == SourceType.SIM && it.id != id && usage.blocked
                    }
                    enforcement.syncKillSwitch(activeBlockedSim)
                }
            }
        }
        prefs.saveUsage(usages)
        _state.value = current.copy(usage = usages)
    }

    fun clearCounters(id: String) {
        val current = _state.value
        val usage = current.usageOf(id).copy(
            rx = 0L, tx = 0L, firedAlerts = emptySet(), overQuota = false,
            reconciledBytes = 0L, platformTotal = 0L, drift = 0L
        )
        val usages = current.usage.toMutableMap().apply { put(id, usage) }
        prefs.saveUsage(usages)
        _state.value = current.copy(usage = usages)
        reEvaluate(id)
    }

    fun setPaused(paused: Boolean) {
        prefs.meteringPaused = paused
        _state.value = _state.value.copy(meteringPaused = paused)
    }

    fun setSampleMillis(millis: Long) {
        val sanitized = SampleIntervals.sanitize(millis)
        prefs.sampleMillis = sanitized
        _state.value = _state.value.copy(sampleMillis = sanitized)
    }

    fun setKillSwitchEnabled(enabled: Boolean) {
        prefs.killSwitchEnabled = enabled
        if (!enabled) enforcement.releaseSim()
        _state.value = _state.value.copy(
            killSwitchEnabled = enabled,
            killSwitchActive = enforcement.isValveEngaged()
        )
    }

    fun killSwitchEnabled(): Boolean = prefs.killSwitchEnabled

    private fun reEvaluate(id: String) {
        scope.launch {
            sampleLock.withLock {
                val current = _state.value
                val source = current.sourceOf(id) ?: return@withLock
                val config = current.configOf(id)
                val usage = current.usageOf(id)
                val result = QuotaEngine.evaluate(
                    source, config, usage,
                    previouslyBlocked = usage.overQuota && config.blockAtQuota
                )
                val usages = current.usage.toMutableMap().apply { put(id, result.usage) }
                result.effects.filterIsInstance<QuotaEngine.Effect.DisableWifi>()
                    .forEach { effect ->
                        val target = current.sourceOf(effect.sourceId)
                        if (target != null && enforcement.disableWifi(target.name)) {
                            disabledWifi += target.id
                        }
                    }
                result.effects.filterIsInstance<QuotaEngine.Effect.EnableWifi>()
                    .forEach { effect ->
                        val target = current.sourceOf(effect.sourceId)
                        if (target != null && disabledWifi.remove(target.id)) {
                            enforcement.enableWifi(target.name)
                        }
                    }
                _state.value = current.copy(usage = usages)
            }
        }
    }

    fun previewAlert(id: String, percent: Int) {
        val current = _state.value
        val source = current.sourceOf(id) ?: return
        val config = current.configOf(id)
        notifier.threshold(
            source, percent, current.usageOf(id).total, config.quotaBytes,
            percent >= 100, config.unit
        )
    }

    fun noteVpnPermission(granted: Boolean) {
        _state.value = _state.value.copy(vpnPermissionGranted = granted)
    }

    fun setServiceRunning(running: Boolean) {
        _state.value = _state.value.copy(serviceRunning = running)
    }

    fun refreshPermissions() {
        if (!initialized) return
        _state.value = _state.value.copy(
            vpnPermissionGranted = enforcement.vpnPrepared(),
            usageAccessGranted = platformStats.hasUsageAccess(),
            locationGranted = enforcement.hasLocation(),
            phoneStateGranted = enforcement.hasPhoneState()
        )
    }

    fun persist(force: Boolean = false) = persist(_state.value.usage, force)

    private fun persist(usages: Map<String, SourceUsage>, force: Boolean) {
        if (!initialized) return
        prefs.saveUsage(usages)
        prefs.saveLedger(pipeline.exportBaselines())
        if (force) prefs.saveConfigs(_state.value.configs)
    }

    /** "Wi-Fi 1.03 GB · SIM 466 MB" — the one-line summary used by the notification. */
    fun summaryLine(): String {
        val s = _state.value
        val head = s.visible.take(3).joinToString(" · ") { src ->
            "${src.name} ${Units.format(s.usageOf(src.id).total, s.configOf(src.id).unit)}"
        }
        return head.ifBlank { "No sources yet" }
    }
}

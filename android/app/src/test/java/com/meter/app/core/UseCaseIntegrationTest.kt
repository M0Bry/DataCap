package com.meter.app.core

import com.meter.app.core.ledger.CounterLedger
import com.meter.app.core.ledger.InterfaceReading
import com.meter.app.core.ledger.PlatformUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests: the whole pipeline — counters → ledger → framing → quota rules → alerts and
 * blocks → reconciliation — driven exactly like the background service drives it, over a
 * simulated device with two SIMs and two Wi-Fi networks.
 *
 * One test per use case, named after the use case, so the suite doubles as the specification.
 */
class UseCaseIntegrationTest {

    private val gib = Units.GIB
    private val mib = Units.MIB

    private val sim1 = Source(
        id = "sim:1", type = SourceType.SIM, name = "Vodafone", detail = "SIM 1",
        iface = "rmnet_data0", subId = 1, slot = 0, carrier = "Vodafone", subscriberId = "60201"
    )
    private val sim2 = Source(
        id = "sim:2", type = SourceType.SIM, name = "Orange", detail = "SIM 2",
        iface = "rmnet_data0", subId = 2, slot = 1, carrier = "Orange", subscriberId = "60202"
    )
    private val home = Source(
        id = "wifi:Home", type = SourceType.WIFI, name = "Home Wi-Fi", detail = "5 GHz", iface = "wlan0"
    )
    private val office = Source(
        id = "wifi:Office", type = SourceType.WIFI, name = "Office", detail = "2.4 GHz", iface = "wlan0"
    )

    private val sources = listOf(sim1, sim2, home, office).associateBy { it.id }

    private fun configs(
        sim1Quota: Long = 3 * gib,
        sim2Quota: Long = 5 * gib,
        wifiQuota: Long = 0L,
        overhead: Int = 0
    ) = mapOf(
        sim1.id to SourceConfig(sim1.id, quotaBytes = sim1Quota, thresholds = listOf(50, 75, 100), overheadBytes = overhead),
        sim2.id to SourceConfig(sim2.id, quotaBytes = sim2Quota, thresholds = listOf(50, 75, 100), overheadBytes = overhead),
        home.id to SourceConfig(home.id, quotaBytes = wifiQuota, thresholds = listOf(50, 75, 100), overheadBytes = overhead),
        office.id to SourceConfig(office.id, quotaBytes = wifiQuota, thresholds = listOf(50, 75, 100), overheadBytes = overhead)
    )

    /** The device: one shared cellular uplink plus one Wi-Fi uplink, as on a DSDS phone. */
    private class FakeDevice {
        var dds = "sim:1"                  // which SIM the system attaches for data
        var wifiSsid: String? = "wifi:Home"
        var cellularRx = 0L
        var cellularTx = 0L
        var wifiRx = 0L
        var wifiTx = 0L

        /**
         * A real phone boots with its interfaces present and their counters at zero, so the
         * meter takes its baselines immediately instead of at the first byte.
         */
        private val cellularUp get() = true
        private val wifiUp get() = wifiSsid != null

        fun use(rx: Long, tx: Long = 0L, over: Medium = Medium.CELLULAR) {
            when (over) {
                Medium.CELLULAR -> { cellularRx += rx; cellularTx += tx }
                Medium.WIFI -> { wifiRx += rx; wifiTx += tx }
            }
        }

        enum class Medium { CELLULAR, WIFI }

        fun readings(): List<InterfaceReading> = buildList {
            if (cellularUp) add(InterfaceReading("rmnet_data0", cellularRx, cellularTx))
            if (wifiUp) add(InterfaceReading("wlan0", wifiRx, wifiTx))
        }

        /** The live ownership map the app builds from ConnectivityManager + SubscriptionManager. */
        fun owners(): Map<String, String> = buildMap {
            if (cellularUp) put("rmnet_data0", dds)
            if (wifiUp) wifiSsid?.let { put("wlan0", it) }
        }
    }

    private class Harness(overhead: Int = 0) {
        val pipeline = Pipeline(CounterLedger())
        var usages: Map<String, SourceUsage> = emptyMap()
        val notices = ArrayList<Triple<String, Int, Boolean>>()
        val effects = ArrayList<QuotaEngine.Effect>()
        var now = 1_700_000_000_000L

        fun tick(device: FakeDevice, sources: Map<String, Source>, configs: Map<String, SourceConfig>) {
            val outcome = pipeline.tick(
                now = now, readings = device.readings(), owners = device.owners(),
                sources = sources, configs = configs, usages = usages
            )
            usages = outcome.usages
            outcome.effects.forEach { effect ->
                effects += effect
                if (effect is QuotaEngine.Effect.Notify) {
                    notices += Triple(effect.sourceId, effect.percent, effect.blocked)
                }
            }
        }
    }

    // ---------------------------------------------------------------- use cases

    @Test
    fun `use case 1 - every source is metered independently`() {
        val device = FakeDevice()
        val h = Harness()
        val cfg = configs()

        h.tick(device, sources, cfg)   // boot: baselines are taken at zero

        device.use(10 * mib, 1 * mib, FakeDevice.Medium.CELLULAR)
        h.tick(device, sources, cfg)

        device.wifiSsid = "wifi:Home"
        device.use(40 * mib, 4 * mib, FakeDevice.Medium.WIFI)
        h.tick(device, sources, cfg)

        assertEquals(10 * mib, h.usages[sim1.id]!!.rx)
        assertEquals(40 * mib, h.usages[home.id]!!.rx)
        assertEquals("SIM 2 saw nothing", 0L, h.usages[sim2.id]!!.total)
        assertEquals("the office network saw nothing", 0L, h.usages[office.id]!!.total)
    }

    @Test
    fun `use case 2 - SIM 1 and SIM 2 never share a counter, even on one shared interface`() {
        val device = FakeDevice()
        val h = Harness()
        val cfg = configs()
        h.tick(device, sources, cfg)                                  // baseline

        device.use(100 * mib)                                         // 100 MB on Vodafone
        h.tick(device, sources, cfg)

        // the user switches the default data SIM to Orange
        device.dds = "sim:2"
        h.tick(device, sources, cfg)
        device.use(250 * mib)                                         // 250 MB on Orange
        h.tick(device, sources, cfg)

        assertEquals("Vodafone keeps exactly its own 100 MB", 100 * mib, h.usages[sim1.id]!!.total)
        assertEquals("Orange keeps exactly its own 250 MB", 250 * mib, h.usages[sim2.id]!!.total)
        assertNotEquals(h.usages[sim1.id]!!.total, h.usages[sim2.id]!!.total)
        assertEquals("nothing was counted twice", 350 * mib, h.usages[sim1.id]!!.total + h.usages[sim2.id]!!.total)
    }

    @Test
    fun `use case 3 - two Wi-Fi networks on one interface are credited to the right SSID`() {
        val device = FakeDevice()
        val h = Harness()
        val cfg = configs()
        h.tick(device, sources, cfg)

        device.wifiSsid = "wifi:Home"
        device.use(500 * mib, 0, FakeDevice.Medium.WIFI)
        h.tick(device, sources, cfg)

        device.wifiSsid = "wifi:Office"
        h.tick(device, sources, cfg)
        device.use(120 * mib, 0, FakeDevice.Medium.WIFI)
        h.tick(device, sources, cfg)

        assertEquals(500 * mib, h.usages[home.id]!!.total)
        assertEquals(120 * mib, h.usages[office.id]!!.total)
    }

    @Test
    fun `use case 4 - thresholds fire once and only the exhausted source is blocked`() {
        val device = FakeDevice()
        val h = Harness()
        val cfg = configs(sim1Quota = 3 * gib, sim2Quota = 5 * gib, wifiQuota = 50 * gib)
        h.tick(device, sources, cfg)

        // 1.6 GB on Vodafone -> the 50 % alert
        device.use((1.6 * gib).toLong())
        h.tick(device, sources, cfg)
        assertTrue(h.notices.any { it.first == sim1.id && it.second == 50 })

        // 2.4 GB -> 75 %
        device.use((0.8 * gib).toLong())
        h.tick(device, sources, cfg)
        assertTrue(h.notices.any { it.first == sim1.id && it.second == 75 })

        // 3.1 GB -> 100 % and the block, with no other source affected
        device.use((0.7 * gib).toLong())
        h.tick(device, sources, cfg)
        assertTrue(h.notices.any { it.first == sim1.id && it.second == 100 && it.third })
        assertTrue(h.effects.any { it is QuotaEngine.Effect.HoldSim && it.sourceId == sim1.id })
        assertTrue(h.usages[sim1.id]!!.blocked)
        assertFalse(h.usages[sim2.id]!!.blocked)
        assertFalse(h.usages[home.id]!!.blocked)
        assertEquals("each threshold fired exactly once", 3, h.notices.count { it.first == sim1.id })
    }

    @Test
    fun `use case 5 - a blocked SIM does not block Wi-Fi or the other SIM`() {
        val device = FakeDevice()
        val h = Harness()
        val cfg = configs(sim1Quota = 1 * gib)
        h.tick(device, sources, cfg)

        device.use(1 * gib)                                    // Vodafone is exhausted
        h.tick(device, sources, cfg)
        assertTrue(h.usages[sim1.id]!!.blocked)

        // the user moves to Wi-Fi: the traffic is still metered, on the Wi-Fi source
        device.wifiSsid = "wifi:Home"
        device.use(2 * gib, 0, FakeDevice.Medium.WIFI)
        h.tick(device, sources, cfg)
        assertFalse("Wi-Fi keeps working", h.usages[home.id]!!.blocked)
        assertEquals(2 * gib, h.usages[home.id]!!.total)

        // and the other SIM as well
        device.dds = "sim:2"
        h.tick(device, sources, cfg)
        device.use(300 * mib)
        h.tick(device, sources, cfg)
        assertEquals(300 * mib, h.usages[sim2.id]!!.total)
        assertFalse(h.usages[sim2.id]!!.blocked)
    }

    @Test
    fun `use case 6 - each source carries its own cycle and rolls over alone`() {
        val device = FakeDevice()
        val h = Harness()
        val cfg = configs().toMutableMap()
        cfg[sim1.id] = cfg[sim1.id]!!.copy(periodDays = 7)
        cfg[sim2.id] = cfg[sim2.id]!!.copy(periodDays = 30)
        h.tick(device, sources, cfg)

        // 400 MB on Vodafone, then some on Orange through its own SIM
        device.use(400 * mib)
        h.tick(device, sources, cfg)
        assertEquals(400 * mib, h.usages[sim1.id]!!.total)

        device.dds = "sim:2"
        h.tick(device, sources, cfg)
        device.use(250 * mib)
        h.tick(device, sources, cfg)
        assertEquals(250 * mib, h.usages[sim2.id]!!.total)

        // eight days later only Vodafone's 7-day cycle has ended
        h.now += 8 * Units.DAY
        device.dds = "sim:1"
        h.tick(device, sources, cfg)
        device.use(10 * mib)
        h.tick(device, sources, cfg)

        assertEquals("Vodafone restarted its cycle", 10 * mib, h.usages[sim1.id]!!.total)
        assertEquals("Orange keeps its own cycle and its bytes", 250 * mib, h.usages[sim2.id]!!.total)
    }

    @Test
    fun `use case 13 - bytes inside a SIM handover window are recovered from the platform ledger`() {
        val device = FakeDevice()
        val h = Harness()
        val cfg = configs()
        h.tick(device, sources, cfg)

        device.use(3 * mib)                       // 3 MB on Vodafone
        h.tick(device, sources, cfg)
        assertEquals(3 * mib, h.usages[sim1.id]!!.total)

        // the user switches to Orange; 4 MB flow before the app can attribute them
        device.dds = "sim:2"
        device.use(4 * mib)
        h.tick(device, sources, cfg)
        // The counter ledger refuses to guess: those bytes belong to neither SIM yet.
        assertEquals("Vodafone keeps exactly its own bytes", 3 * mib, h.usages[sim1.id]!!.total)
        assertEquals(0L, h.usages[sim2.id]!!.total)

        // The platform's own per-subscription ledger knows, so the gap is closed from there.
        val outcome = h.pipeline.reconcile(
            h.now, sim2, cfg[sim2.id]!!, h.usages[sim2.id]!!,
            com.meter.app.core.ledger.PlatformUsage(rx = 4 * mib, tx = 0)
        )
        assertEquals("not one byte of the handover is missed", 4 * mib, outcome.usage.rx)
        assertEquals(0L, outcome.usage.drift)
        assertEquals("and none of it was given to the other SIM", 3 * mib, h.usages[sim1.id]!!.total)
    }

    @Test
    fun `use case 7 - no byte is lost in the connection overhead`() {
        val device = FakeDevice()
        // 36 B per packet on top of the kernel's own count, as an operator would bill
        val h = Harness()
        val cfg = configs(overhead = 36)
        h.tick(device, sources, cfg)

        device.use(rx = 1400 * 10, tx = 200)                   // ten full packets down, one up
        h.tick(device, sources, cfg)

        val total = h.usages[sim1.id]!!.total
        assertTrue("the framing allowance is added", total > 1400 * 10 + 200)
        assertEquals(1400 * 10 + 200 + 11 * 36L, total)
    }

    @Test
    fun `use case 8 - traffic the app could not see is recovered from the platform ledger`() {
        val h = Harness()
        val cfg = configs()
        var usage = SourceUsage(sim1.id, rx = 1 * gib, cycleStart = h.now - Units.DAY)

        // the platform says the SIM really carried 1.2 GB in this cycle
        val (recovered, added) = h.pipeline
            .reconcile(h.now, sim1, cfg[sim1.id]!!, usage, PlatformUsage(rx = 1_200_000_000L, tx = 0))
            .let { it.usage to it.addedBytes }

        assertEquals("nothing is missed", 1 * gib + added, recovered.rx)
        assertEquals("our ledger ends up exactly on the platform's figure", 1_200_000_000L, recovered.rx)
        assertTrue("the correction is recorded", recovered.reconciledBytes > 0)
        assertEquals(0L, recovered.drift)

        // the following reconciliation has nothing left to do
        val (again, secondAdd) = h.pipeline
            .reconcile(h.now, sim1, cfg[sim1.id]!!, recovered, PlatformUsage(rx = recovered.rx, tx = 0))
            .let { it.usage to it.addedBytes }
        assertEquals(0L, secondAdd)
        assertEquals(recovered.rx, again.rx)
    }

    @Test
    fun `use case 9 - a reconciliation that crosses the quota blocks the source`() {
        val h = Harness()
        val cfg = configs(sim1Quota = 1 * gib)
        val usage = SourceUsage(sim1.id, rx = 900 * mib, cycleStart = h.now)

        val outcome = h.pipeline.reconcile(
            h.now, sim1, cfg[sim1.id]!!, usage, PlatformUsage(rx = 1 * gib + 10 * mib, tx = 0)
        )
        assertTrue(outcome.usage.blocked)
        assertTrue(outcome.effects.any { it is QuotaEngine.Effect.HoldSim })
    }

    @Test
    fun `use case 10 - SIM 1 and SIM 2 keep separate quotas at the same percentage`() {
        val device = FakeDevice()
        val h = Harness()
        val cfg = configs(sim1Quota = 1 * gib, sim2Quota = 10 * gib)
        h.tick(device, sources, cfg)

        device.dds = "sim:2"                                   // start on Orange
        h.tick(device, sources, cfg)                           // the switch is noticed here
        device.use((5 * gib).toLong())
        h.tick(device, sources, cfg)
        assertEquals(50f, QuotaEngine.percent(h.usages[sim2.id]!!, cfg[sim2.id]!!), 0.01f)
        assertFalse("half of Orange is not half of Vodafone", h.usages[sim2.id]!!.blocked)

        device.dds = "sim:1"                                   // switch to Vodafone
        h.tick(device, sources, cfg)
        device.use((1 * gib).toLong())
        h.tick(device, sources, cfg)
        assertTrue("Vodafone is exhausted on its own smaller quota", h.usages[sim1.id]!!.blocked)
        assertFalse("Orange is untouched", h.usages[sim2.id]!!.blocked)
    }

    @Test
    fun `use case 11 - resetting one source leaves every other source alone`() {
        val device = FakeDevice()
        val h = Harness()
        val cfg = configs()
        h.tick(device, sources, cfg)
        device.use(300 * mib)
        device.use(200 * mib, 0, FakeDevice.Medium.WIFI)
        h.tick(device, sources, cfg)

        // what resetCycle(sim1) does
        val reset = h.usages.toMutableMap()
        reset[sim1.id] = reset[sim1.id]!!.copy(
            rx = 0, tx = 0, cycleStart = h.now, firedAlerts = emptySet(), overQuota = false
        )
        h.usages = reset

        assertEquals(0L, h.usages[sim1.id]!!.total)
        assertEquals(200 * mib, h.usages[home.id]!!.total)
        assertEquals(0L, h.usages[sim2.id]!!.total)

        // and the counters keep working from that moment
        device.use(50 * mib)
        h.tick(device, sources, cfg)
        assertEquals(50 * mib, h.usages[sim1.id]!!.total)
    }

    @Test
    fun `use case 12 - the meter's own accounting never disagrees with the raw counters`() {
        val device = FakeDevice()
        val h = Harness()
        val cfg = configs(overhead = 0)                        // raw kernel counts, no allowance
        h.tick(device, sources, cfg)

        repeat(20) { step ->
            device.use(rx = 111 * step + 7L, tx = 3L)
            h.tick(device, sources, cfg)
        }
        val expected = (0 until 20).sumOf { (111 * it + 7).toLong() }
        assertEquals(expected, h.usages[sim1.id]!!.rx)
        assertEquals(20 * 3L, h.usages[sim1.id]!!.tx)
    }
}

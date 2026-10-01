package com.meter.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the app, tested one by one: thresholds fire once per cycle, 100 % blocks only the
 * source that reached it, cycles roll over on their own, and Unlimited never blocks.
 */
class QuotaEngineTest {

    private val sim1 = Source(
        id = "sim:1", type = SourceType.SIM, name = "Vodafone", detail = "SIM 1",
        iface = "rmnet_data0", subId = 1, slot = 0, carrier = "Vodafone"
    )
    private val sim2 = sim1.copy(id = "sim:2", name = "Orange", detail = "SIM 2", subId = 2, slot = 1)
    private val wifi = Source(
        id = "wifi:Home", type = SourceType.WIFI, name = "Home Wi-Fi", detail = "5 GHz", iface = "wlan0"
    )

    private fun config(id: String, quota: Long, thresholds: List<Int> = listOf(50, 75, 100)) =
        SourceConfig(id = id, quotaBytes = quota, thresholds = thresholds)

    private val gib = Units.GIB

    @Test
    fun `a threshold fires exactly once per cycle`() {
        val cfg = config("sim:1", 3 * gib)
        var usage = SourceUsage("sim:1")

        usage = QuotaEngine.evaluate(sim1, cfg, usage.copy(rx = (1.4 * gib).toLong())).usage
        val first = QuotaEngine.evaluate(sim1, cfg, usage.copy(rx = (1.6 * gib).toLong()))
        assertEquals(listOf(50), first.effects.filterIsInstance<QuotaEngine.Effect.Notify>().map { it.percent })

        val again = QuotaEngine.evaluate(sim1, cfg, first.usage.copy(rx = (1.8 * gib).toLong()))
        assertTrue(
            "50 % must not fire twice",
            again.effects.filterIsInstance<QuotaEngine.Effect.Notify>().isEmpty()
        )
    }

    @Test
    fun `crossing several thresholds at once fires each of them`() {
        val cfg = config("sim:1", 3 * gib)
        val result = QuotaEngine.evaluate(sim1, cfg, SourceUsage("sim:1", rx = (2.4 * gib).toLong()))
        val fired = result.effects.filterIsInstance<QuotaEngine.Effect.Notify>().map { it.percent }
        assertEquals(listOf(50, 75), fired)
    }

    @Test
    fun `100 percent blocks the source and only that source`() {
        val cfg = config("sim:1", 3 * gib)
        val result = QuotaEngine.evaluate(sim1, cfg, SourceUsage("sim:1", rx = 3 * gib))
        val effects = result.effects
        assertTrue(result.usage.overQuota)
        assertTrue(result.usage.blocked)
        assertTrue(
            "an exhausted SIM is held",
            effects.any { it is QuotaEngine.Effect.HoldSim && it.sourceId == "sim:1" }
        )
        assertFalse(
            "no other source may be touched",
            effects.any { it is QuotaEngine.Effect.DisableWifi || it is QuotaEngine.Effect.ReleaseSim }
        )
    }

    @Test
    fun `an exhausted Wi-Fi network is disconnected while mobile data is untouched`() {
        val cfg = config("wifi:Home", 50 * gib, thresholds = listOf(100))
        val result = QuotaEngine.evaluate(wifi, cfg, SourceUsage("wifi:Home", rx = 50 * gib))
        assertTrue(result.effects.any { it is QuotaEngine.Effect.DisableWifi })
        assertFalse(result.effects.any { it is QuotaEngine.Effect.HoldSim })
    }

    @Test
    fun `an unlimited source never blocks`() {
        val cfg = config("wifi:Home", 0)
        val result = QuotaEngine.evaluate(wifi, cfg, SourceUsage("wifi:Home", rx = 900 * gib))
        assertFalse(result.usage.overQuota)
        assertFalse(result.usage.blocked)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `blocking can be switched off for a source that still wants alerts`() {
        val cfg = config("sim:1", 1 * gib).copy(blockAtQuota = false)
        val result = QuotaEngine.evaluate(sim1, cfg, SourceUsage("sim:1", rx = 1 * gib))
        assertTrue(result.usage.overQuota)
        assertFalse("the user asked for alerts only", result.usage.blocked)
        assertTrue(result.effects.none { it is QuotaEngine.Effect.HoldSim })
    }

    @Test
    fun `a cycle rolls over and clears usage, alerts and the block`() {
        val cfg = config("sim:1", 1 * gib)
        val start = 1_000_000L
        val blocked = SourceUsage(
            "sim:1", rx = 1 * gib, cycleStart = start,
            firedAlerts = setOf(50, 75, 100), overQuota = true, manualBlock = true
        )
        val after = QuotaEngine.rolloverIfDue(cfg, blocked, start + 30 * Units.DAY + 1)!!
        assertEquals(0L, after.rx)
        assertTrue(after.firedAlerts.isEmpty())
        assertFalse(after.blocked)
        assertEquals(start + 30 * Units.DAY, after.cycleStart)
    }

    @Test
    fun `a cycle that is still running is left alone`() {
        val cfg = config("sim:1", 1 * gib)
        val usage = SourceUsage("sim:1", rx = 500, cycleStart = 1_000_000L)
        assertNull(QuotaEngine.rolloverIfDue(cfg, usage, 1_000_000L + 10 * Units.DAY))
    }

    @Test
    fun `a phone that was off for months lands on the cycle that is running now`() {
        val cfg = config("sim:1", 1 * gib)
        val started = 1_000_000L                 // the cycle really began at some point
        val now = started + 95 * Units.DAY       // ... and then the phone was off for a while
        val after = QuotaEngine.rolloverIfDue(cfg, SourceUsage("sim:1", cycleStart = started), now)!!
        assertEquals("three whole cycles have passed since", started + 90 * Units.DAY, after.cycleStart)
        assertTrue(after.cycleStart <= now)
        assertTrue("and the cycle it lands on is still running", after.cycleStart + 30 * Units.DAY > now)
    }

    @Test
    fun `a source that has never run gets its cycle from now`() {
        val cfg = config("sim:1", 1 * gib)
        val now = 42L
        assertEquals(now, QuotaEngine.rolloverIfDue(cfg, SourceUsage("sim:1"), now)!!.cycleStart)
    }

    @Test
    fun `an alert-only source is over quota but never blocked`() {
        val cfg = config("sim:1", 1 * gib).copy(blockAtQuota = false)
        // it was blocked before the user switched the setting off
        val result = QuotaEngine.evaluate(
            sim1, cfg, SourceUsage("sim:1", rx = 1 * gib, overQuota = true), previouslyBlocked = true
        )
        assertTrue(result.usage.overQuota)
        assertFalse(result.usage.blocked)
        assertTrue("the block that was in force is lifted", result.effects.any { it is QuotaEngine.Effect.ReleaseSim })
    }

    @Test
    fun `each source is evaluated on its own dictionary of usage`() {
        val cfg1 = config("sim:1", 3 * gib)
        val cfg2 = config("sim:2", 5 * gib)
        val wifiCfg = config("wifi:Home", 50 * gib)

        val usage1 = QuotaEngine.evaluate(sim1, cfg1, SourceUsage("sim:1", rx = 3 * gib)).usage
        val usage2 = QuotaEngine.evaluate(sim2, cfg2, SourceUsage("sim:2", rx = 1 * gib)).usage
        val usageW = QuotaEngine.evaluate(wifi, wifiCfg, SourceUsage("wifi:Home", rx = 1 * gib)).usage

        assertTrue(usage1.blocked)
        assertFalse("SIM 2 keeps working", usage2.blocked)
        assertFalse("Wi-Fi keeps working", usageW.blocked)
        assertEquals("SIM 2 has used 1 of its 5 GB", 20f, QuotaEngine.percent(usage2, cfg2), 0.01f)
    }

    @Test
    fun `the scoped meter aggregates a family without inventing a block`() {
        val sources = mapOf(sim1.id to sim1, sim2.id to sim2)
        val configs = mapOf(
            sim1.id to config(sim1.id, 3 * gib),
            sim2.id to config(sim2.id, 5 * gib)
        )
        val usages = mapOf(
            sim1.id to SourceUsage(sim1.id, rx = 3 * gib, overQuota = true),
            sim2.id to SourceUsage(sim2.id, rx = 1 * gib)
        )
        val scope = QuotaEngine.scope(
            ids = listOf(sim1.id, sim2.id), label = "All SIM cards",
            sources = sources, configs = configs, usages = usages, now = 30 * Units.DAY
        )
        assertEquals(8 * gib, scope.quota)
        assertEquals(4 * gib, scope.used)
        assertEquals(50f, scope.percent, 0.01f)
        assertFalse("the family is not blocked just because one member is", scope.blocked)
    }
}

package com.meter.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for every list and menu in the app: the filter pills, the source chips, the
 * notification-threshold list, the quota presets and stepper, and the sample-interval options.
 *
 * The screens call exactly these functions, so a passing suite means the lists cannot misbehave
 * underneath the UI.
 */
class ListsAndMenusTest {

    private val sim1 = Source("sim:1", SourceType.SIM, "Vodafone", "SIM 1", "rmnet_data0", subId = 1, slot = 0)
    private val sim2 = Source("sim:2", SourceType.SIM, "Orange", "SIM 2", "rmnet_data0", subId = 2, slot = 1)
    private val home = Source("wifi:Home", SourceType.WIFI, "Home Wi-Fi", "5 GHz", "wlan0")
    private val office = Source("wifi:Office", SourceType.WIFI, "Office", "2.4 GHz", "wlan0")
    private val all = listOf(sim1, sim2, home, office)

    private fun snapshot(
        sources: List<Source> = all,
        filter: FamilyFilter = FamilyFilter.ALL,
        selected: Map<SourceType, String> = mapOf(SourceType.SIM to "sim:1", SourceType.WIFI to "wifi:Home")
    ) = MeterSnapshot(sources = sources, filter = filter, selected = selected)

    // ------------------------------------------------------------- filter pills

    @Test
    fun `the pills list exactly the family they name`() {
        assertEquals(all, Selection.visible(all, FamilyFilter.ALL))
        assertEquals(listOf(sim1, sim2), Selection.visible(all, FamilyFilter.SIM))
        assertEquals(listOf(home, office), Selection.visible(all, FamilyFilter.WIFI))
    }

    @Test
    fun `all is the only aggregate - a family always resolves to one source`() {
        assertEquals(listOf("sim:1", "sim:2", "wifi:Home", "wifi:Office"), snapshot().selectedIds)
        assertEquals(listOf("sim:1"), snapshot(filter = FamilyFilter.SIM).selectedIds)
        assertEquals(listOf("wifi:Home"), snapshot(filter = FamilyFilter.WIFI).selectedIds)
    }

    @Test
    fun `tapping a chip changes that family's selection and nothing else`() {
        val after = Selection.afterSourceTap(snapshot(), "sim:2")
        assertEquals("sim:2", after[SourceType.SIM])
        assertEquals("wifi:Home", after[SourceType.WIFI])
    }

    @Test
    fun `opening a family with no valid selection picks its first member`() {
        val result = Selection.afterFilterTap(snapshot(selected = emptyMap()), FamilyFilter.SIM)
        assertEquals("sim:1", result.selected[SourceType.SIM])
        assertFalse(result.emptyFamily)
    }

    @Test
    fun `a selection that disappeared is repaired instead of leaving the page empty`() {
        val withoutSim1 = listOf(sim2, home)
        val result = Selection.afterFilterTap(snapshot(sources = withoutSim1), FamilyFilter.SIM)
        assertEquals("the SIM was pulled, so SIM 2 becomes the selection", "sim:2", result.selected[SourceType.SIM])
    }

    @Test
    fun `an empty family reports itself so the UI can explain instead of showing nothing`() {
        val wifiOnly = listOf(home)
        val result = Selection.afterFilterTap(snapshot(sources = wifiOnly), FamilyFilter.SIM)
        assertTrue(result.emptyFamily)
    }

    // ------------------------------------------------------------- source list

    @Test
    fun `a new source gets a config and a running cycle`() {
        val now = 1_700_000_000_000L
        val result = Selection.reconcileSources(
            previous = MeterSnapshot(),
            discovered = listOf(sim1, home),
            now = now
        ) { SourceConfig(it.id, quotaBytes = 3 * Units.GIB) }
        assertEquals(2, result.configs.size)
        assertEquals(now, result.usage["sim:1"]!!.cycleStart)
        assertEquals(3 * Units.GIB, result.configs["sim:1"]!!.quotaBytes)
    }

    @Test
    fun `a source that disappears keeps its config and counters`() {
        val previous = MeterSnapshot(
            sources = listOf(sim1, sim2),
            configs = mapOf("sim:2" to SourceConfig("sim:2", quotaBytes = 5 * Units.GIB)),
            usage = mapOf("sim:2" to SourceUsage("sim:2", rx = 42, cycleStart = 10L)),
            selected = mapOf(SourceType.SIM to "sim:2")
        )
        val result = Selection.reconcileSources(previous, listOf(sim1), 20L) { SourceConfig(it.id) }
        assertEquals("the removed SIM's quota survives", 5 * Units.GIB, result.configs["sim:2"]!!.quotaBytes)
        assertEquals(42L, result.usage["sim:2"]!!.rx)
        assertEquals("the selection moves to the SIM that is present", "sim:1", result.selected[SourceType.SIM])
    }

    // ------------------------------------------------------- threshold list

    @Test
    fun `adding a threshold never duplicates and stays sorted`() {
        val added = ThresholdList.add(listOf(50, 75, 100), 25)!!
        assertEquals(listOf(25, 50, 75, 100), added)
        val duplicate = ThresholdList.add(listOf(50, 75, 100), 75)
        assertNull("an existing threshold is not added twice", duplicate)
    }

    @Test
    fun `add without a value picks the next sensible suggestion`() {
        val added = ThresholdList.add(listOf(50, 75, 100))!!
        assertEquals(listOf(10, 50, 75, 100), added)
    }

    @Test
    fun `editing a threshold keeps the list ordered`() {
        val edited = ThresholdList.update(listOf(50, 75, 100), index = 2, value = 60)
        assertEquals(listOf(50, 60, 75), edited)
    }

    @Test
    fun `an out of range edit is clamped, not rejected`() {
        assertEquals(listOf(1, 75, 100), ThresholdList.update(listOf(50, 75, 100), 0, 0))
        // 999 clamps to 100, which the list already holds, so the duplicate collapses
        assertEquals(listOf(75, 100), ThresholdList.update(listOf(50, 75, 100), 0, 999))
    }

    @Test
    fun `deleting the last threshold leaves an empty list that can be refilled`() {
        // No notifications at all is a valid choice; the quota still blocks at 100 %.
        assertEquals(emptyList<Int>(), ThresholdList.remove(listOf(50), 0))
        assertEquals(emptyList<Int>(), ThresholdList.remove(emptyList(), 0))
        assertEquals(listOf(10), ThresholdList.add(emptyList()))
    }

    @Test
    fun `an index that no longer exists is ignored`() {
        assertEquals(listOf(50, 75, 100), ThresholdList.remove(listOf(50, 75, 100), 9))
        assertEquals(listOf(50, 75, 100), ThresholdList.update(listOf(50, 75, 100), -1, 20))
    }

    @Test
    fun `the list stops growing at the cap instead of overflowing`() {
        var list = emptyList<Int>()
        repeat(ThresholdList.MAX) { list = ThresholdList.add(list)!! }
        assertEquals(ThresholdList.MAX, list.size)
        assertNull("the Add button is disabled at the cap", ThresholdList.add(list))
    }

    // ---------------------------------------------------------- quota controls

    @Test
    fun `the quota stepper uses sensible steps`() {
        assertEquals(1_100 * Units.MIB, QuotaSteps.step(1_000 * Units.MIB, 1))
        assertEquals(3_500 * Units.MIB, QuotaSteps.step(3 * Units.GIB, 1))
        assertEquals(11 * Units.GIB, QuotaSteps.step(10 * Units.GIB, 1))
        assertEquals(0L, QuotaSteps.step(50 * Units.MIB, -1))
    }

    @Test
    fun `a preset is marked active only when the quota matches it`() {
        val threeGb = QuotaSteps.PRESETS.first { it.first == "3 GB" }
        val unlimited = QuotaSteps.PRESETS.first { it.first == "Unlimited" }
        assertTrue(QuotaSteps.isActive(3 * Units.GIB, threeGb))
        assertFalse(QuotaSteps.isActive(4 * Units.GIB, threeGb))
        assertTrue(QuotaSteps.isActive(0L, unlimited))
    }

    @Test
    fun `every preset is a valid quota`() {
        QuotaSteps.PRESETS.forEach { (label, bytes) ->
            assertTrue("$label must not be negative", bytes >= 0L)
            if (label != "Unlimited") assertTrue("$label must be at least 1 MB", bytes >= Units.MIB)
        }
    }

    // ------------------------------------------------------- sample intervals

    @Test
    fun `sample intervals are clamped to the supported range`() {
        assertEquals(SampleIntervals.MIN, SampleIntervals.sanitize(1L))
        assertEquals(SampleIntervals.MAX, SampleIntervals.sanitize(10 * 60_000L))
        assertEquals(5_000L, SampleIntervals.sanitize(5_000L))
    }

    @Test
    fun `every option in the interval menu survives sanitising`() {
        SampleIntervals.OPTIONS.forEach { (label, millis) ->
            assertEquals("$label must be a valid interval", millis, SampleIntervals.sanitize(millis))
            assertNotNull(label)
        }
    }

    // ----------------------------------------------------------------- units

    @Test
    fun `units format exactly like the design`() {
        assertEquals("1.10 GB", Units.format(1_181_116_006L, UnitMode.AUTO))
        assertEquals("1.50 GB", Units.format(1_610_612_736L, UnitMode.AUTO))
        assertEquals("410 MB", Units.format(429_916_160L, UnitMode.AUTO))
        assertEquals("55 MB", Units.format(57_671_680L, UnitMode.AUTO))
        assertEquals("Unlimited", Units.formatQuota(0L, UnitMode.AUTO))
    }

    @Test
    fun `the display unit setting is honoured in both modes`() {
        val oneAndAHalfGb = 1_610_612_736L
        assertEquals("1536 MB", Units.format(oneAndAHalfGb, UnitMode.MB))
        assertEquals("1.50 GB", Units.format(oneAndAHalfGb, UnitMode.GB))
        assertEquals("3" to "GB", Units.quotaParts(3 * Units.GIB, UnitMode.AUTO))
        assertEquals("500" to "MB", Units.quotaParts(500 * Units.MIB, UnitMode.AUTO))
        assertEquals("∞" to "", Units.quotaParts(0L, UnitMode.AUTO))
    }

    @Test
    fun `rates are shown in megabits per second like the design`() {
        assertEquals("12.4 Mbit/s", Units.formatRate(1_550_000L))
        assertEquals("0.0 Mbit/s", Units.formatRate(0L))
    }

    @Test
    fun `timestamps look like the design`() {
        val text = Units.formatDateTime(1_756_684_800_000L)      // 2025-09-01T12:00Z-ish
        assertTrue("expected a 'MMM dd, h:mm AM/PM' shape but was '$text'", Regex("^[A-Z][a-z]{2} \\d{2}, \\d{1,2}:\\d{2} (AM|PM)$").matches(text))
    }
}

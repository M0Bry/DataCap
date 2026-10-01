package com.meter.app.core

import com.meter.app.core.ledger.CounterLedger
import com.meter.app.core.ledger.InterfaceReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the counter arithmetic — the layer that decides whether a byte is counted once,
 * twice or not at all.
 */
class CounterLedgerTest {

    private fun reading(iface: String, rx: Long, tx: Long, rxP: Long = -1, txP: Long = -1) =
        InterfaceReading(iface, rx, tx, rxP, txP)

    @Test
    fun `first sight of an interface only sets a baseline`() {
        val ledger = CounterLedger()
        val deltas = ledger.sample(
            listOf(reading("rmnet_data0", 5_000, 1_000)),
            mapOf("rmnet_data0" to "sim:1")
        )
        assertTrue("nothing may be credited before the baseline is known", deltas.isEmpty())
        assertEquals(5_000L, ledger.baselineOf("rmnet_data0"))
    }

    @Test
    fun `a delta is credited to the owner of the interface exactly once`() {
        val ledger = CounterLedger()
        val owners = mapOf("rmnet_data0" to "sim:1")
        ledger.sample(listOf(reading("rmnet_data0", 1_000, 200)), owners)

        val first = ledger.sample(listOf(reading("rmnet_data0", 1_600, 260)), owners)
        assertEquals(1, first.size)
        assertEquals("sim:1", first[0].sourceId)
        assertEquals(600L, first[0].rx)
        assertEquals(60L, first[0].tx)

        val second = ledger.sample(listOf(reading("rmnet_data0", 1_600, 260)), owners)
        assertTrue("an unchanged counter must not produce a delta", second.isEmpty())
    }

    @Test
    fun `nothing migrates between sources when an interface changes hands`() {
        val ledger = CounterLedger()
        ledger.sample(
            listOf(reading("rmnet_data0", 10_000, 500)),
            mapOf("rmnet_data0" to "sim:1")
        )
        val sim1 = ledger.sample(
            listOf(reading("rmnet_data0", 14_000, 900)),
            mapOf("rmnet_data0" to "sim:1")
        )
        assertEquals(4_000L, sim1.first().rx)

        // the user moves mobile data from SIM 1 to SIM 2 on the same shared interface
        val handover = ledger.sample(
            listOf(reading("rmnet_data0", 14_100, 950)),
            mapOf("rmnet_data0" to "sim:2")
        )
        assertEquals(1, handover.size)
        assertTrue(handover[0].rebased)
        assertTrue("SIM 2 must not inherit SIM 1's bytes", handover[0].isEmpty)

        // and from here on it is SIM 2 that is credited
        val sim2 = ledger.sample(
            listOf(reading("rmnet_data0", 14_600, 1_020)),
            mapOf("rmnet_data0" to "sim:2")
        )
        assertEquals(1, sim2.size)
        assertEquals("sim:2", sim2[0].sourceId)
        assertEquals(500L, sim2[0].rx)
    }

    @Test
    fun `a 32-bit counter wrap does not lose the bytes in the gap`() {
        val ledger = CounterLedger()
        val owners = mapOf("rmnet_data0" to "sim:1")
        val nearMax = CounterLedger.UINT32_MAX - 100
        ledger.sample(listOf(reading("rmnet_data0", nearMax, 0)), owners)

        val wrapped = ledger.sample(listOf(reading("rmnet_data0", 400, 0)), owners)
        assertEquals("100 bytes to the wrap plus 400 after it", 501L, wrapped.first().rx)
    }

    @Test
    fun `a hard counter reset credits what has flowed since the reset instead of dropping it`() {
        val ledger = CounterLedger()
        val owners = mapOf("wlan0" to "wifi:home")
        ledger.sample(listOf(reading("wlan0", 9_000_000_000L, 100)), owners)

        val afterReset = ledger.sample(listOf(reading("wlan0", 7_000, 10)), owners)
        assertEquals(7_000L, afterReset.first().rx)
        assertEquals(10L, afterReset.first().tx)
    }

    @Test
    fun `interfaces without an owner are ignored`() {
        val ledger = CounterLedger()
        val deltas = ledger.sample(
            listOf(reading("lo", 1_000, 1_000), reading("tun0", 500, 500)),
            mapOf("rmnet_data0" to "sim:1")
        )
        assertTrue("loopback and our own tunnel must never be metered", deltas.isEmpty())
    }

    @Test
    fun `baselines survive a process restart and still credit the gap`() {
        val first = CounterLedger()
        val owners = mapOf("rmnet_data0" to "sim:1")
        first.sample(listOf(reading("rmnet_data0", 1_000, 100)), owners)
        val exported = first.export()

        // the app is killed, the phone keeps using data, then the app comes back
        val second = CounterLedger()
        second.importBaselinesForTest(exported)
        val afterRestart = second.sample(listOf(reading("rmnet_data0", 91_000, 5_100)), owners)
        assertEquals("the 90 kB that flowed while the meter was dead are not lost", 90_000L, afterRestart.first().rx)
        assertEquals(5_000L, afterRestart.first().tx)
    }

    @Test
    fun `packet counters travel with the delta when the kernel exposes them`() {
        val ledger = CounterLedger()
        val owners = mapOf("rmnet_data0" to "sim:1")
        ledger.sample(listOf(reading("rmnet_data0", 0, 0, 0, 0)), owners)
        val deltas = ledger.sample(listOf(reading("rmnet_data0", 4_200, 700, 3, 1)), owners)
        assertEquals(3L, deltas.first().rxPackets)
        assertEquals(1L, deltas.first().txPackets)
    }

    // the ledger's own import() is public; this alias keeps the test readable
    private fun CounterLedger.importBaselinesForTest(lines: List<String>) = import(lines)
}

package com.meter.app.core

import com.meter.app.core.ledger.CounterLedger
import com.meter.app.core.ledger.OverheadModel
import com.meter.app.core.ledger.OverheadPolicy
import com.meter.app.core.ledger.PlatformUsage
import com.meter.app.core.ledger.ReconciliationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the two halves of "not one kilobyte may be missed": the framing allowance that
 * closes the gap up to what the operator bills, and the reconciliation that closes the gap left
 * by anything the app could not see at all.
 */
class PrecisionTest {

    private fun delta(rx: Long, tx: Long, rxP: Long = -1, txP: Long = -1) =
        CounterLedger.Delta("sim:1", "rmnet_data0", rx, tx, rxP, txP)

    @Test
    fun `measured packet counts give an exact framing allowance`() {
        val billed = OverheadModel.bill(delta(10_000, 2_000, rxP = 8, txP = 2), OverheadPolicy.forSim(36))
        assertEquals(10_000 + 8 * 36L, billed.rx)
        assertEquals(2_000 + 2 * 36L, billed.tx)
        assertTrue("8 packets were measured, not guessed", !billed.packetsEstimated)
    }

    @Test
    fun `without packet counts the MTU estimate still covers the traffic`() {
        val policy = OverheadPolicy.forWifi(24)
        val bytes = 1500L * 4 + 10           // 6010 bytes => 5 packets at MTU 1500
        val billed = OverheadModel.bill(delta(bytes, 0), policy)
        assertEquals(6010L + 5 * 24L, billed.rx)
        assertEquals(0L, billed.tx)
        assertTrue(billed.packetsEstimated)
        assertTrue("the estimate rounds up, so it can never under-report", billed.rx >= bytes)
    }

    @Test
    fun `a raw policy adds nothing at all`() {
        val billed = OverheadModel.bill(delta(1234, 567, 9, 9), OverheadPolicy.RAW)
        assertEquals(1234L, billed.rx)
        assertEquals(567L, billed.tx)
    }

    @Test
    fun `the platform ledger shows up as a correction when our ledger is behind`() {
        val correction = ReconciliationPolicy.correction(
            ledgerRx = 1_000_000, ledgerTx = 100_000,
            platform = PlatformUsage(1_050_000, 100_000)
        )
        assertNotNull(correction)
        assertEquals("the missing 50 kB are recovered", 50_000L, correction!!.rx)
        assertEquals(0L, correction.tx)
    }

    @Test
    fun `a correction is never negative - over-counting is left alone`() {
        val correction = ReconciliationPolicy.correction(
            ledgerRx = 1_100_000, ledgerTx = 100_000,
            platform = PlatformUsage(1_050_000, 100_000)
        )
        assertNull("we never walk a source's usage back", correction)
        assertEquals(
            -50_000L,
            ReconciliationPolicy.drift(1_100_000, 100_000, PlatformUsage(1_050_000, 100_000))
        )
    }

    @Test
    fun `sampling jitter below a page of data is not chased`() {
        assertNull(
            ReconciliationPolicy.correction(
                1_000_000, 100_000, PlatformUsage(1_000_000 + 2048, 100_000)
            )
        )
    }

    @Test
    fun `an empty platform total leaves the ledger untouched`() {
        val correction = ReconciliationPolicy.correction(5_000, 0, PlatformUsage(0, 0))
        assertNull(correction)
    }

    @Test
    fun `the overhead share is reported for the sanity check card`() {
        val share = ReconciliationPolicy.overheadShare(ledgerTotal = 1_050_000, platformTotal = 1_000_000)
        assertTrue("we counted 5% more than the platform", kotlin.math.abs(share - 5f) < 0.01f)
    }
}

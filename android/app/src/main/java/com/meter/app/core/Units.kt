package com.meter.app.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Number formatting. 1024-based, exactly like the Meter design:
 *   1.10 GB / 1.50 GB / 410 MB — i.e. 1157627904 bytes shows as "1.10 GB".
 *
 * UnitMode.AUTO follows the design: MiB below 1 GiB, GiB above.
 */
object Units {

    const val KIB = 1024L
    const val MIB = 1024L * 1024L
    const val GIB = 1024L * 1024L * 1024L
    const val DAY = 24L * 60L * 60L * 1000L

    fun format(bytes: Long, mode: UnitMode): String {
        val b = if (bytes < 0) 0 else bytes
        return when (mode) {
            UnitMode.MB -> trim(b.toDouble() / MIB, if (b < 10 * MIB) 1 else 0) + " MB"
            UnitMode.GB -> if (b < GIB) trim(b.toDouble() / MIB, 1) + " MB"
                           else trim(b.toDouble() / GIB, if (b >= 10 * GIB) 1 else 2) + " GB"
            UnitMode.AUTO -> if (b >= GIB) trim(b.toDouble() / GIB, 2) + " GB"
                             else if (b >= MIB) trim(b.toDouble() / MIB, if (b < 10 * MIB) 1 else 0) + " MB"
                             else trim(b.toDouble() / KIB, 0) + " KB"
        }
    }

    /** "1.50 GB" / "410 MB" — the Quota row shows the same unit style as Used. */
    fun formatQuota(bytes: Long, mode: UnitMode): String =
        if (bytes <= 0L) "Unlimited" else format(bytes, mode)

    /** Splits a quota into value + unit for the big Setup number: "3" + "GB", not "3.00 GB". */
    fun quotaParts(bytes: Long, mode: UnitMode): Pair<String, String> =
        if (bytes <= 0L) "∞" to ""
        else when (mode) {
            UnitMode.MB -> trim(bytes.toDouble() / MIB, 0) to "MB"
            UnitMode.GB -> nice(bytes.toDouble() / GIB) to "GB"
            UnitMode.AUTO -> if (bytes >= GIB) nice(bytes.toDouble() / GIB) to "GB"
                             else trim(bytes.toDouble() / MIB, 0) to "MB"
        }

    /** Two decimals maximum, trailing zeros removed. */
    private fun nice(v: Double): String =
        trim(v, 2).trimEnd('0').trimEnd('.')

    fun formatRate(bytesPerSecond: Long): String =
        trim(bytesPerSecond * 8.0 / 1_000_000.0, 1) + " Mbit/s"

    private fun trim(v: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", v)

    // ------------------------------------------------------------------ dates

    private val dayMonth = SimpleDateFormat("MMM dd", Locale.US)
    private val clock12 = SimpleDateFormat("h:mm a", Locale.US)

    /** "Sep 01, 12:00 AM" — exactly the shape used by the design. */
    fun formatDateTime(millis: Long): String =
        dayMonth.format(Date(millis)) + ", " + clock12.format(Date(millis))

    fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun addDays(millis: Long, days: Int): Long =
        millis + days * DAY

    fun daysBetween(from: Long, to: Long): Double =
        ((to - from).toDouble() / DAY).coerceAtLeast(1.0 / 24.0)

    /** Byte formatter used by the storage layer (compact, unambiguous). */
    fun toStorage(bytes: Long): String = bytes.toString()
}

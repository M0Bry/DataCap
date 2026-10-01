package com.meter.app.core.platform

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.util.Log
import com.meter.app.core.Source
import com.meter.app.core.SourceType
import com.meter.app.core.ledger.PlatformUsage

/**
 * The platform's own usage ledger — the one behind Settings → Data usage.
 *
 * It is the authority for the promise "not one kilobyte may be missed": it is cumulative, it is
 * maintained by the system even while our app is dead or throttled, it counts traffic no other
 * API reports (tethering, system updates, anything the OS itself accounts for), and it is queried
 * **per subscription**, which is the only way to get a true per-SIM figure on a device where both
 * SIMs share one interface.
 *
 * It needs the *Usage access* special permission, which the user grants in system settings; the
 * app asks for it in Setup → Permissions and works fine (with the kernel counters only) without
 * it. Reconciliation then becomes a no-op rather than an error.
 */
class PlatformStats(private val context: Context) {

    private val manager: NetworkStatsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(NetworkStatsManager::class.java)
        } else null

    fun hasUsageAccess(): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * Everything the system attributes to one subscription between two instants.
     * [subscriberId] may be empty when telephony permission was denied: the query is then made for
     * all mobile traffic and the caller decides who it belongs to (that is only safe when the
     * device has a single data SIM, which the engine checks before using it).
     */
    fun mobile(subscriberId: String, start: Long, end: Long): PlatformUsage? = query(
        ConnectivityManager.TYPE_MOBILE,
        subscriberId.ifBlank { null },
        start,
        end
    )

    /** Everything the system attributes to Wi-Fi. Per-SSID figures do not exist in this API. */
    fun wifi(start: Long, end: Long): PlatformUsage? =
        query(ConnectivityManager.TYPE_WIFI, null, start, end)

    /** Best query for one source, or null when it cannot be answered reliably. */
    fun forSource(
        source: Source,
        subscriberId: String,
        start: Long,
        end: Long,
        singleDataSim: Boolean
    ): PlatformUsage? = when (source.type) {
        SourceType.SIM -> when {
            subscriberId.isNotBlank() -> mobile(subscriberId, start, end)
            // Without telephony permission the platform figure cannot be attributed to one SIM.
            singleDataSim -> mobile("", start, end)
            else -> null
        }
        SourceType.WIFI -> wifi(start, end)
    }

    private fun query(type: Int, subscriberId: String?, start: Long, end: Long): PlatformUsage? {
        if (!hasUsageAccess()) return null
        val nsm = manager ?: return null
        return runCatching {
            // One bucket holds everything the platform attributed to this type / subscription in
            // the window, with no state, uid or tag filtering: nothing can hide from it.
            val bucket = nsm.querySummaryForDevice(type, subscriberId, start, end) ?: return null
            PlatformUsage(bucket.rxBytes.coerceAtLeast(0L), bucket.txBytes.coerceAtLeast(0L))
        }.getOrElse {
            Log.d(TAG, "query($type, $subscriberId) failed: ${it.message}")
            null
        }
    }

    private companion object {
        const val TAG = "Meter.PlatformStats"
    }
}

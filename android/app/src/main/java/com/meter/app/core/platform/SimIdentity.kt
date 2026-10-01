package com.meter.app.core.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.meter.app.core.ledger.OwnershipMap

/**
 * Keeps SIM 1 and SIM 2 apart.
 *
 * This is the class that fixes the "both SIMs are counted as one source" problem. Two facts make
 * it non-trivial on Android:
 *
 *  · a dual-SIM phone usually attaches **only the default data SIM** (DSDS): there is one cellular
 *    uplink interface, `rmnet_data0`, and it carries whichever subscription is currently the data
 *    subscription. The interface does not belong to a SIM — it belongs to the *subscription using
 *    it right now*;
 *  · some devices (and all DSDA/premium ones) do expose one interface per SIM
 *    (`rmnet_data0` + `rmnet_data1`, `ccmni0` + `ccmni1`, `pdp0` + `pdp1`), where the trailing
 *    index matches the SIM slot.
 *
 * [owners] answers both cases:
 *   1. the interface of the *active* network belongs to the default data subscription — always;
 *   2. any other cellular interface is matched to a slot by its index;
 *   3. anything left over falls back to the default data subscription, never to "all SIMs".
 *
 * The result is fed to the counter ledger, which re-bases on every ownership change — so when the
 * user moves data from SIM 1 to SIM 2, the counters of one SIM are never added to the other.
 */
class SimIdentity(private val context: Context) {

    data class SimSlot(
        val subId: Int,
        val slot: Int,
        val carrier: String,
        val subscriberId: String,
        val sourceId: String,
        val iface: String
    )

    private val subscriptions: SubscriptionManager? =
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

    private val telephony: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val networks = Networks(context)

    private val hasPhoneState: Boolean
        get() = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /** All inserted SIMs, whether or not they currently carry data. */
    fun sims(uplinks: List<String> = emptyList()): List<SimSlot> {
        val active = activeSubscriptions()
        val defaultSub = defaultDataSubId()
        val cellular = uplinks.filter { InterfaceCounters.isCellular(it) }
        return active.map { info ->
            val slot = if (info.simSlotIndex >= 0) info.simSlotIndex else 0
            SimSlot(
                subId = info.subscriptionId,
                slot = slot,
                carrier = info.carrierName?.toString()?.trim().orEmpty(),
                subscriberId = subscriberIdOf(info.subscriptionId),
                sourceId = "sim:${info.subscriptionId}",
                iface = interfaceForSlot(slot, cellular, defaultSub == info.subscriptionId)
            )
        }
    }

    /**
     * who owns which interface, at this instant.
     *
     * @param activeWifiSourceId the SSID currently associated, if known
     * @param extraUplinks interfaces that exist but are not in the ConnectivityManager list
     */
    fun owners(
        uplinks: List<String>,
        sims: List<SimSlot>,
        activeWifiSourceId: String?,
        defaultWifiSourceId: String? = null,
        extraUplinks: List<String> = emptyList()
    ): OwnershipMap {
        val map = LinkedHashMap<String, String>()
        val activeIface = networks.activeInterface()
        val defaultSub = defaultDataSubId()
        val defaultSourceId = sims.firstOrNull { it.subId == defaultSub }?.sourceId
            ?: sims.firstOrNull()?.sourceId

        (uplinks + extraUplinks).distinct().forEach { iface ->
            when {
                InterfaceCounters.isWifi(iface) ->
                    (activeWifiSourceId ?: defaultWifiSourceId)?.let { map[iface] = it }

                InterfaceCounters.isCellular(iface) -> {
                    val owner = when {
                        // 1. the interface the system is actually routing through
                        iface == activeIface && defaultSourceId != null -> defaultSourceId
                        // 2. a per-SIM interface: the index matches the SIM slot
                        else -> sims.firstOrNull { it.slot == indexOfIface(iface) }?.sourceId
                            ?: sims.firstOrNull { it.iface == iface }?.sourceId
                            ?: defaultSourceId
                    }
                    if (owner != null) map[iface] = owner
                }
            }
        }
        return map
    }

    /** True when the phone exposes one uplink per SIM (then the split is exact in the kernel). */
    fun hasPerSimInterfaces(uplinks: List<String>): Boolean =
        uplinks.count { InterfaceCounters.isCellular(it) } > 1

    fun defaultDataSubId(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)
        } else -1

    fun subscriberIdOf(subId: Int): String = runCatching {
        @Suppress("MissingPermission")
        telephony?.createForSubscriptionId(subId)?.subscriberId ?: ""
    }.getOrElse {
        Log.d(TAG, "subscriberId unavailable for sub $subId: ${it.message}")
        ""
    }

    /** The subscription a source id refers to, for reconciliation queries. */
    fun subIdOf(sourceId: String): Int = sourceId.removePrefix("sim:").toIntOrNull() ?: -1

    /** How many SIMs are currently able to carry data. */
    fun attachedDataSims(): Int = activeSubscriptions().size

    // ------------------------------------------------------------------ internal

    private fun activeSubscriptions(): List<SubscriptionInfo> {
        if (!hasPhoneState && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return emptyList()
        return runCatching {
            @Suppress("MissingPermission")
            subscriptions?.activeSubscriptionInfoList ?: emptyList()
        }.getOrElse {
            Log.d(TAG, "subscriptions unavailable: ${it.message}")
            emptyList()
        }
    }

    /**
     * The interface a SIM is using.
     *
     * When there is exactly one cellular uplink, that interface is shared and this returns it for
     * every SIM — the live ownership map decides who gets the bytes at any moment. With several
     * uplinks, the index of the interface is matched to the slot.
     */
    private fun interfaceForSlot(slot: Int, cellular: List<String>, isDefaultData: Boolean): String {
        if (cellular.isEmpty()) return ""
        if (cellular.size == 1) return cellular.first()
        cellular.firstOrNull { indexOfIface(it) == slot }?.let { return it }
        return if (isDefaultData) cellular.first() else cellular.getOrElse(slot) { cellular.first() }
    }

    /** `rmnet_data1` / `ccmni1` / `pdp0` → 1 / 1 / 0. Unknown shapes give -1. */
    fun indexOfIface(iface: String): Int {
        val match = Regex("^(rmnet_data|rmnet|ccmni|pdp|seth|wwan|clat)(\\d+)$").find(iface)
            ?: return -1
        return match.groupValues[2].toIntOrNull() ?: -1
    }

    private companion object {
        const val TAG = "Meter.SimIdentity"
    }
}

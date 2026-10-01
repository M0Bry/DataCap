package com.meter.app.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.meter.app.core.Source
import com.meter.app.core.SourceType
import com.meter.app.state.MeterEngine

/**
 * Turning a quota decision into reality, per source type.
 *
 * Wi-Fi — a normal app really can cut one network:
 *   `WifiManager.disconnect()` drops the association now and `disableNetwork(netId)` stops the
 *   phone from rejoining that SSID. The other Wi-Fi networks and mobile data are untouched, which
 *   is exactly the requirement. Re-enabling happens on reset/unblock with `enableNetwork()`.
 *
 * SIM — Android exposes no public API to switch a single subscription's data off, so the only
 *   mechanism available to an ordinary app is the OPTIONAL local-VPN kill switch: while the
 *   exhausted SIM is the device's active data path, traffic is dropped; as soon as the phone
 *   moves to Wi-Fi (or the other SIM) the tunnel is torn down and that source works normally.
 *   That gives the specified semantics — only the exhausted source is affected — without root.
 *   Users who prefer to keep the VPN slot free can switch it off in Setup; the app then simply
 *   keeps notifying (and shows the source as blocked in the UI).
 */
class Enforcement(private val context: Context) {

    private val wifi: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    // ------------------------------------------------------------------- wifi

    fun disableWifi(ssid: String): Boolean {
        val wm = wifi ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "no location permission: cannot match SSID $ssid")
            }
            val netId = configuredNetId(ssid)
            if (netId >= 0) {
                @Suppress("DEPRECATION")
                run { wm.disableNetwork(netId) }
            }
            @Suppress("DEPRECATION")
            run { wm.disconnect() }
            true
        }.getOrElse {
            Log.w(TAG, "disableWifi($ssid) failed: ${it.message}")
            false
        }
    }

    fun enableWifi(ssid: String): Boolean {
        val wm = wifi ?: return false
        return runCatching {
            val netId = configuredNetId(ssid)
            @Suppress("DEPRECATION")
            if (netId >= 0) wm.enableNetwork(netId, true) else true
        }.getOrElse {
            Log.w(TAG, "enableWifi($ssid) failed: ${it.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun configuredNetId(ssid: String): Int {
        val wm = wifi ?: return -1
        val list: List<WifiConfiguration> = runCatching { wm.configuredNetworks }
            .getOrNull() ?: return -1
        return list.firstOrNull { conf ->
            val confSsid = conf.SSID?.trim('"')
            confSsid != null && confSsid == ssid
        }?.networkId ?: -1
    }

    // -------------------------------------------------------------------- sim

    @Volatile
    private var valveEngaged = false

    /**
     * The valve state machine, called on every sample with the source that is currently carrying
     * the default route:
     *   · active source is an exhausted SIM  -> tunnel up (traffic on that SIM stops);
     *   · active source is anything else     -> tunnel down immediately, so Wi-Fi and the other
     *                                           SIM keep working exactly as specified.
     */
    fun syncKillSwitch(activeBlockedSim: Source?) {
        when {
            activeBlockedSim != null && !valveEngaged -> engageKillSwitch(activeBlockedSim)
            activeBlockedSim == null && valveEngaged -> releaseSim()
        }
    }

    fun isValveEngaged() = valveEngaged

    /** Kept for an explicit user action ("Block this source"): engage right away. */
    fun holdSim(source: Source) {
        if (source.type != SourceType.SIM) return
        syncKillSwitch(source)
    }

    private fun engageKillSwitch(source: Source) {
        if (!MeterEngine.killSwitchEnabled()) {
            Log.d(TAG, "valve disabled by user; ${source.name} stays connected (notifications only)")
            return
        }
        if (VpnService.prepare(context) != null) {
            Log.d(TAG, "VPN permission missing; cannot hold ${source.name} yet")
            return
        }
        runCatching {
            context.startService(
                Intent(context, KillSwitchVpnService::class.java)
                    .setAction(KillSwitchVpnService.ACTION_ENGAGE)
                    .putExtra(KillSwitchVpnService.EXTRA_REASON, source.name)
            )
            valveEngaged = true
        }.onFailure { Log.w(TAG, "engage failed: ${it.message}") }
    }

    fun releaseSim() {
        if (!valveEngaged) return
        runCatching {
            context.startService(
                Intent(context, KillSwitchVpnService::class.java)
                    .setAction(KillSwitchVpnService.ACTION_RELEASE)
            )
            valveEngaged = false
        }.onFailure { Log.w(TAG, "release failed: ${it.message}") }
    }

    // ------------------------------------------------------------- permissions

    fun vpnPrepared(): Boolean = runCatching { VpnService.prepare(context) == null }
        .getOrDefault(false)

    fun hasLocation(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasPhoneState(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "Meter.Enforce"
    }
}

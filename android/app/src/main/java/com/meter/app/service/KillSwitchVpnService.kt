package com.meter.app.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * A deliberately tiny VpnService whose only job is to be a valve.
 *
 * It establishes a black-hole tunnel (a tun device whose packets are never read, so the kernel
 * drops them) for the whole address space. It is engaged **only** while the source that ran out
 * of quota is the device's active data path, and released the moment the phone moves to another
 * source — Wi-Fi, the other SIM — or the user resets/unblocks the source.
 *
 * Cost when engaged: one file descriptor and no packet processing at all. Cost when released:
 * zero — the service stops and the tunnel disappears, so the VPN slot is free for anything else.
 *
 * The app's own traffic is excluded from the tunnel so notifications and the foreground service
 * keep working while the valve is closed.
 */
class KillSwitchVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var reason: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENGAGE -> {
                reason = intent.getStringExtra(EXTRA_REASON) ?: "a data source"
                engage()
            }
            ACTION_RELEASE -> release()
            else -> if (tun == null) return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun engage() {
        if (tun != null) return
        val builder = Builder()
            .setSession("Meter quota hold ($reason)")
            .addAddress(TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)
            .setMtu(1500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { Log.d(TAG, "could not exclude self: ${it.message}") }
        }
        // The tunnel is not metered: it carries nothing, it only closes a valve.
        runCatching { builder.setMetered(false) }
        tun = runCatching { builder.establish() }.getOrElse {
            Log.w(TAG, "establish failed: ${it.message}")
            null
        }
        Log.i(TAG, "kill switch engaged for $reason (tun=${tun != null})")
    }

    private fun release() {
        runCatching { tun?.close() }
        tun = null
        Log.i(TAG, "kill switch released")
        stopSelf()
    }

    /** The user revoked the VPN from system settings: back off cleanly. */
    override fun onRevoke() {
        release()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { tun?.close() }
        tun = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Meter.KillSwitch"
        private const val TUN_ADDRESS = "10.111.222.1"

        const val ACTION_ENGAGE = "com.meter.app.action.ENGAGE"
        const val ACTION_RELEASE = "com.meter.app.action.RELEASE"
        const val EXTRA_REASON = "reason"
    }
}

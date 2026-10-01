package com.meter.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meter.app.state.MeterEngine

/** Metering must survive a reboot (and an app update) without the user opening anything. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                MeterEngine.init(context)
                MeterEngine.refreshSources()
                MeterService.start(context)
            }
        }
    }
}

/** Daily housekeeping: cycle rollover, interface re-discovery, state flush. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DAILY_TICK) return
        MeterEngine.init(context)
        MeterEngine.refreshSources()
        MeterEngine.persist(force = true)
        // Re-arm: some OEMs clear alarms after a reboot even with a sticky service.
        runCatching { context.startService(Intent(context, MeterService::class.java)) }
    }

    companion object {
        const val ACTION_DAILY_TICK = "com.meter.app.action.DAILY_TICK"
    }
}

/**
 * Connectivity changes are the moments where attribution can go wrong, so the source list is
 * rebuilt and the sample baseline re-read: switching from one Wi-Fi to another, or from Wi-Fi to
 * a SIM, never leaks bytes from one source into the other.
 */
class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MeterEngine.init(context)
        MeterEngine.refreshSources()
    }
}

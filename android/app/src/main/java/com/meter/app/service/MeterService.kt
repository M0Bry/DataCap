package com.meter.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.meter.app.state.MeterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the meter alive while the app is closed — the whole point of the project.
 *
 * It is a *special use* foreground service with no wake locks, no GPS and no polling of
 * anything expensive: the sampling loop inside [MeterEngine] is a handful of kernel counter
 * reads every few seconds, and the notification is IMPORTANCE_MIN so it never makes a sound.
 *
 * It also owns the housekeeping that must survive a screen-off device:
 *  · a daily [AlarmReceiver] tick to roll cycles over and reconcile names;
 *  · release/engage of the optional SIM kill switch when the active network changes.
 */
class MeterService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notifyJob: Job? = null
    private lateinit var notifier: Notifier

    /**
     * The default connection changing is the one event that can break attribution, so it is
     * observed live instead of with a broadcast (implicit CONNECTIVITY_CHANGE broadcasts are no
     * longer delivered to manifest receivers on modern Android).
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            MeterEngine.refreshSources()
        }

        override fun onLost(network: Network) {
            MeterEngine.refreshSources()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            MeterEngine.refreshSources()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        MeterEngine.init(this)
        startForeground(Notifier.ID_FOREGROUND, notifier.foreground(MeterEngine.summaryLine()))
        MeterEngine.startSampling()
        MeterEngine.setServiceRunning(true)
        runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.registerDefaultNetworkCallback(networkCallback)
        }
        scheduleDailyTick()
        notifyJob = scope.launch {
            MeterEngine.state.collect {
                delay(20_000L)
                notifier.updateForeground(MeterEngine.summaryLine())
            }
        }
        Log.i(TAG, "meter service up")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // A sticky restart after a reboot/kill: make sure we are metering again.
                MeterEngine.init(this)
                MeterEngine.startSampling()
                startForeground(Notifier.ID_FOREGROUND, notifier.foreground(MeterEngine.summaryLine()))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        notifyJob?.cancel()
        runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(networkCallback)
        }
        MeterEngine.setServiceRunning(false)
        MeterEngine.stopSampling()
        Log.i(TAG, "meter service down")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** One alarm a day: rolls cycles over even if the device sat idle the whole time. */
    private fun scheduleDailyTick() {
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_DAILY_TICK)
        val pi = PendingIntent.getBroadcast(
            this, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val at = System.currentTimeMillis() + AlarmManager.INTERVAL_HALF_DAY
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }.onFailure { Log.w(TAG, "alarm scheduling failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "Meter.Service"
        const val ACTION_START = "com.meter.app.action.START_METERING"
        const val ACTION_STOP = "com.meter.app.action.STOP_METERING"

        fun start(context: Context) {
            val intent = Intent(context, MeterService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, MeterService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}

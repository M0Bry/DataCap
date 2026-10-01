package com.meter.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.meter.app.MainActivity
import com.meter.app.R
import com.meter.app.core.Source
import com.meter.app.core.UnitMode
import com.meter.app.core.Units

/**
 * Three notifications, no more:
 *  · a silent, persistent one that keeps the meter alive (foreground service);
 *  · one per threshold crossing (50 %, 75 %, 100 % … whatever the user configured);
 *  · one when a source gets blocked, saying explicitly that the other sources still work.
 *
 * Notification ids are stable per source+threshold so a repeated firing replaces instead of
 * stacking, which matters when a user configures many thresholds.
 */
class Notifier(private val context: Context) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_ALERTS, context.getString(R.string.channel_alerts_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.channel_alerts_desc)
                    enableVibration(true)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_STATUS, context.getString(R.string.channel_status_name),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = context.getString(R.string.channel_status_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    fun foreground(summary: String): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setContentTitle(context.getString(R.string.svc_title))
            .setContentText(summary)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun updateForeground(summary: String) {
        runCatching { nm.notify(ID_FOREGROUND, foreground(summary)) }
    }

    fun threshold(
        source: Source,
        percent: Int,
        used: Long,
        quota: Long,
        blocked: Boolean,
        unit: UnitMode = UnitMode.AUTO
    ) {
        val title = context.getString(
            if (blocked) R.string.alert_title_blocked else R.string.alert_title_threshold
        )
        val body = if (blocked) {
            "${source.name} — 100% of its quota used (${Units.format(used, unit)}). " +
                "Traffic on this source is stopped; your other sources keep working."
        } else {
            "${source.name} — $percent% of its data quota used " +
                "(${Units.format(used, unit)} of ${Units.format(quota, unit)})."
        }
        post(source.id.hashCode() * 100 + percent, title, body, highPriority = percent >= 100)
    }

    fun info(source: Source, title: String, body: String) =
        post(source.id.hashCode() * 100 + 1, title, body, highPriority = false)

    private fun post(id: Int, title: String, body: String, highPriority: Boolean) {
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(
                if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
            )
            .build()
        runCatching { nm.notify(id, n) }
    }

    fun cancel(id: Int) = runCatching { nm.cancel(id) }.let { }

    companion object {
        const val CH_ALERTS = "meter.alerts"
        const val CH_STATUS = "meter.status"
        const val ID_FOREGROUND = 1001
    }
}

package com.aura.launcher.timer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aura.launcher.AuraApp
import com.aura.launcher.MainActivity
import com.aura.launcher.R
import kotlinx.coroutines.launch

/**
 * Telling somebody the countdown is over.
 *
 * A timer whose only announcement is a number on the home screen is a timer
 * that works while you are looking at it, which is the one situation nobody
 * needs one for. So the end of a countdown is booked with the system as an
 * alarm, and arrives as a notification whether or not the launcher is running
 * — or, for that matter, loaded.
 *
 * Exact where the system allows it, and this is the one place in this app where
 * the difference genuinely matters: a reminder that lands a few minutes late is
 * a reminder, but an egg timer that goes off at eleven past is wrong. Windowed
 * when the permission is absent, because a timer that is sometimes late is
 * still better than one that refuses to exist.
 */
object TimerAlarm {

    private const val ACTION = "com.aura.launcher.TIMER_DONE"
    private const val REQUEST = 8802
    private const val NOTIFICATION_ID = 8802
    private const val CHANNEL_ID = "timer"
    private const val TAG = "TimerAlarm"

    /** Books, moves or cancels the alarm so it matches [state]. */
    fun sync(context: Context, state: TimerState, now: Long = System.currentTimeMillis()) {
        val at = Timers.endsAt(state, now)
        if (at == null) {
            cancel(context)
            return
        }
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = intent(context, create = true) ?: return

        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
        runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                manager.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
            }
        }.onFailure {
            // A refused exact alarm must not lose the timer.
            runCatching { manager.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending) }
                .onFailure { failure -> Log.w(TAG, "Could not book the timer", failure) }
        }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        intent(context, create = false)?.let { pending ->
            runCatching { manager.cancel(pending) }
            pending.cancel()
        }
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private fun intent(context: Context, create: Boolean): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST,
        Intent(context, TimerReceiver::class.java).setAction(ACTION),
        (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE) or
            PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * The countdown reaching zero.
     *
     * Also re-books itself after a restart: alarms do not survive a reboot, and
     * a timer set for two hours that silently forgot to go off because the
     * phone restarted is worse than one that was never set.
     */
    class TimerReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val app = context.applicationContext as? AuraApp ?: return
            when (intent?.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.intent.action.QUICKBOOT_POWERON" ->
                    app.scope.launch { sync(app, app.timers.state.value) }

                ACTION -> app.scope.launch {
                    val now = System.currentTimeMillis()
                    if (!app.timers.state.value.isDone(now)) return@launch
                    app.timers.edit { Timers.finish(it, now) }
                    post(app)
                }
            }
        }
    }

    private fun post(context: Context) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            context,
            REQUEST,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(context.getString(R.string.timer_done_title))
            .setContentText(context.getString(R.string.timer_done_body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        // Without POST_NOTIFICATIONS this posts nothing and says nothing about
        // it. Asking for the grant belongs to the permissions screen.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.timer_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.timer_channel_desc)
                }
            )
        }
    }

    /** How late a windowed alarm may be when exact ones are not allowed. */
    private const val WINDOW_MS = 60_000L
}

package com.aura.launcher.control

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aura.launcher.R

/**
 * Says screen control was given up, and offers the one tap back.
 *
 * The giving up is not negotiable — banks refuse to run while an accessibility
 * service is enabled, and [SensitiveApps] hands the permission over rather than
 * argue. What is negotiable is the return trip. Android has no way for an app
 * to re-grant itself an accessibility service, which is the entire point of the
 * grant, so somebody has to walk to system settings and flip the switch by
 * hand. There is no version of this feature where that does not happen.
 *
 * So the work here is making that walk one step instead of four. The offer
 * already existed, buried in Aura's own permissions screen — which meant
 * opening the launcher, opening settings, finding the row, and only then
 * arriving where Android keeps the switch. A notification put up at the moment
 * it happens lands the user on that page directly, from wherever they are.
 *
 * It is deliberately ordinary: no sound, low priority, dismissible. Nothing
 * about it is urgent — the phone works, one feature is off — and a bank trip
 * that ended in a buzz would be worse than the feature being quietly dead.
 */
object ScreenControlPausedNotification {

    private const val CHANNEL_ID = "screen_control_paused"
    private const val NOTIFICATION_ID = 4713

    /** @param appLabel the app it stepped aside for, for the one line of text. */
    fun show(context: Context, appLabel: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager, context)

        // Straight to Android's accessibility page. Not to Aura's own settings:
        // that is the screen the user is already going through by hand, and
        // adding a tap to reach it would be a notification that saves nothing.
        // Lands on Aura's row, highlighted.
        val target = ScreenController.accessibilityIntent(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(context.getString(R.string.screen_control_paused_title))
            .setContentText(context.getString(R.string.screen_control_paused_text, appLabel))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.screen_control_paused_body, appLabel)
                )
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Could not post the paused notice", it) }
    }

    /**
     * Takes it down, for when the service is back.
     *
     * Called from the service connecting rather than from the tap, because the
     * tap only opens a page — whether the switch was actually flipped there is
     * something only the service coming back can say.
     */
    fun dismiss(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private fun ensureChannel(manager: NotificationManager, context: Context) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.screen_control_paused_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.screen_control_paused_channel_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    private const val TAG = "ScreenControlPaused"
}

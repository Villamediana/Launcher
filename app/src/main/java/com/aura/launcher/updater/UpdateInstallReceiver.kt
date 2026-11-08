package com.aura.launcher.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aura.launcher.AuraApp
import com.aura.launcher.R

/**
 * The installer's answers, and the "update ready" notification's button.
 *
 * A session reports back here: "needs the user" (start the confirmation
 * Android hands over), failure (said on the update card), or nothing at all on
 * success — the app is replaced and restarted.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? AuraApp ?: return
        when (intent.action) {
            ACTION_INSTALL -> {
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                val pending = goAsync()
                Thread {
                    try {
                        app.updater.install()
                    } finally {
                        pending.finish()
                    }
                }.start()
            }

            ACTION_STATUS -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                UpdaterDiagnostics.log("install", "Session status=$status $message")
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(confirm) }
                            .onFailure { app.updater.installFailed(context.getString(R.string.updater_install_failed, it.message.orEmpty())) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> Unit
                    PackageInstaller.STATUS_FAILURE_ABORTED -> app.updater.reset()
                    else -> app.updater.installFailed(
                        context.getString(R.string.updater_install_failed, message.ifBlank { "status $status" }),
                    )
                }
            }
        }
    }

    companion object {
        const val ACTION_STATUS = "com.aura.launcher.UPDATE_STATUS"
        const val ACTION_INSTALL = "com.aura.launcher.UPDATE_INSTALL"
        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 4801

        /** The "ready" notification was tapped: the launcher is in front, install now. */
        fun installFromNotification(app: AuraApp) {
            app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            Thread { app.updater.install() }.start()
        }

        /** "Launcher x.y.z is ready — tap to install." One tap, no screens in between. */
        fun notifyReady(context: Context, versionName: String) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.updater_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
            // Opens the launcher, which starts the install with it in front. From a
            // receiver in the background, Android 12+ may refuse to show the
            // installer's confirmation — a tap that would have done nothing.
            val install = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, com.aura.launcher.MainActivity::class.java)
                    .setAction(ACTION_INSTALL)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.updater_ready_title, versionName))
                .setContentText(context.getString(R.string.updater_ready_text))
                .setContentIntent(install)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.updater_install_now), install)
                .build()
            runCatching { manager.notify(NOTIFICATION_ID, notification) }
        }
    }
}

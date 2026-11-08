package com.aura.launcher.todo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aura.launcher.AuraApp
import com.aura.launcher.MainActivity
import com.aura.launcher.R
import com.aura.launcher.data.HomeWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Posts a to-do when its time comes, with a "Done" button that ticks it off
 * without opening anything — and re-arms the next one.
 *
 * Boot and update are here because both drop alarms.
 */
class TodoReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? AuraApp ?: return
        val action = intent.action
        val key = when (action) {
            ACTION_REMIND, ACTION_DONE -> TodoAlarms.keyOf(intent) ?: return
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> null
            else -> return
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    if (app.todos.data.value == TodoData()) app.todos.load()
                    val slot = key?.let { TodoAlarms.slotOf(it) }
                    if (slot != null && action == ACTION_DONE) {
                        app.todos.setDone(slot.first, slot.second, done = true)
                        context.getSystemService(NotificationManager::class.java)
                            ?.cancel(notificationId(slot.first))
                    } else if (slot != null) {
                        announce(app, slot.first, slot.second)
                    }
                    TodoAlarms.sync(app, app.todos.data.value)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not handle $action", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun announce(app: AuraApp, todoId: String, slotAt: Long) {
        if (!app.currentSettings.value.shows(HomeWidget.TODO)) return
        val now = System.currentTimeMillis()
        if (slotAt > now + SLACK_MS) return
        val state = app.todos.data.value
        val todo = state.todos.firstOrNull { it.id == todoId } ?: return
        if (todo.archived || !todo.remind || TodoMath.isDone(state, todoId, slotAt)) return

        ensureChannel(app)
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        val id = notificationId(todoId)
        val done = PendingIntent.getBroadcast(
            app,
            id,
            Intent(app, TodoReminderReceiver::class.java).apply {
                action = ACTION_DONE
                data = Uri.parse("aura-todo://${Uri.encode(TodoAlarms.key(todoId, slotAt))}")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            app,
            id,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(todo.title)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, app.getString(R.string.todo_notif_done), done)
        if (todo.note.isNotBlank()) builder.setContentText(todo.note)
        runCatching { manager.notify(id, builder.build()) }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.todo_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.todo_channel_description)
                    enableVibration(true)
                    setShowBadge(true)
                }
            )
        }
    }

    private fun notificationId(todoId: String): Int = "todo-$todoId".hashCode()

    companion object {
        private const val TAG = "TodoReminderReceiver"
        const val ACTION_REMIND = "com.aura.launcher.TODO_REMIND"
        const val ACTION_DONE = "com.aura.launcher.TODO_DONE"
        private const val CHANNEL_ID = "todo"
        private const val SLACK_MS = 60_000L
        private const val TIMEOUT_MS = 8_000L
    }
}

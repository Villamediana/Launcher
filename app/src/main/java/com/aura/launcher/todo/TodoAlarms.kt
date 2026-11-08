package com.aura.launcher.todo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.aura.launcher.data.HomeWidget

/**
 * Keeps one alarm armed per to-do: its next timed occurrence that wants a
 * reminder. Re-stated after every write and every time one fires, so only a
 * handful exist at once and nothing has to be cancelled in bulk.
 */
object TodoAlarms {

    private const val PREFIX = "todo"

    fun key(todoId: String, slotAt: Long): String = "$PREFIX-$todoId-$slotAt"

    @Synchronized
    fun sync(context: Context, data: TodoData) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val wanted = LinkedHashMap<String, Long>()
        // A widget taken off the home screen stops ringing: the switch in
        // Settings › Widgets is where somebody goes to make it stop.
        if (shows(app)) {
            data.todos
                .filter { !it.archived && it.remind && it.timed }
                .mapNotNull { todo ->
                    TodoMath.slots(todo, now + MIN_LEAD_MS, now + 2 * TodoMath.DAY)
                        .firstOrNull { !TodoMath.isDone(data, todo.id, it) }
                        ?.let { todo.id to it }
                }
                .sortedBy { it.second }
                .take(MAX_ALARMS)
                .forEach { (id, at) -> wanted[key(id, at)] = at }
        }

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = armedKeys ?: prefs.getStringSet(PREF_ARMED, emptySet()).orEmpty()
        for (k in previous) if (!wanted.containsKey(k)) cancel(app, k)
        for ((k, at) in wanted) arm(app, k, at)
        val next = LinkedHashSet(wanted.keys)
        armedKeys = next
        prefs.edit().putStringSet(PREF_ARMED, next).apply()
    }

    private fun arm(context: Context, key: String, at: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, key, create = true) ?: return
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
        runCatching {
            if (exact) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else manager.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending)
        }.onFailure {
            Log.w(TAG, "Could not arm $key exactly; falling back", it)
            runCatching { manager.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending) }
        }
    }

    private fun cancel(context: Context, key: String) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, key, create = false) ?: return
        runCatching {
            manager.cancel(pending)
            pending.cancel()
        }
    }

    private fun pendingIntent(context: Context, key: String, create: Boolean): PendingIntent? {
        val intent = Intent(context, TodoReminderReceiver::class.java).apply {
            action = TodoReminderReceiver.ACTION_REMIND
            data = Uri.parse("aura-todo://${Uri.encode(key)}")
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return runCatching { PendingIntent.getBroadcast(context, key.hashCode(), intent, flags) }.getOrNull()
    }

    internal fun keyOf(intent: Intent): String? = intent.data?.encodedSchemeSpecificPart
        ?.removePrefix("//")
        ?.let { Uri.decode(it) }
        ?.takeIf { it.isNotEmpty() }

    /** `todo-<id>-<slot>` back into its parts. Ids never contain a dash. */
    internal fun slotOf(key: String): Pair<String, Long>? {
        if (!key.startsWith("$PREFIX-")) return null
        val cut = key.lastIndexOf('-')
        if (cut <= PREFIX.length) return null
        val at = key.substring(cut + 1).toLongOrNull() ?: return null
        return key.substring(PREFIX.length + 1, cut) to at
    }

    private fun shows(context: Context): Boolean {
        val app = context as? com.aura.launcher.AuraApp ?: return true
        return app.currentSettings.value.shows(HomeWidget.TODO)
    }

    @Volatile
    private var armedKeys: Set<String>? = null

    private const val TAG = "TodoAlarms"
    private const val PREFS = "aura_todo_alarms"
    private const val PREF_ARMED = "armed_keys"
    private const val WINDOW_MS = 3 * 60 * 1000L
    private const val MIN_LEAD_MS = 2_000L
    private const val MAX_ALARMS = 12
}

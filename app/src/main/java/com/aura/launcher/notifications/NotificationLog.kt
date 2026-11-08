package com.aura.launcher.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.aura.launcher.data.readTextAtomically
import com.aura.launcher.data.writeTextAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One notification as it arrived, for the "while you were away" part of Seu dia. */
@Serializable
data class LoggedNotification(
    val conversation: String,
    val at: Long,
    val app: String,
    val title: String,
    val text: String,
    /** The filter called it noise; kept out of the summary. */
    val hidden: Boolean = false,
)

/**
 * What arrived recently, kept for a day and a half.
 *
 * The home screen's list is what is still in the shade; this is what came in
 * at all — including what was read on the lock screen and swiped away — so the
 * morning summary can say what happened overnight even when the shade has
 * already been cleared. One entry per conversation, the newest wording.
 *
 * Diagnostics-grade data: it is on the phone only, never in a backup, and
 * gone after [KEEP_MS].
 */
object NotificationLog {

    private val json = Json { ignoreUnknownKeys = true }
    private val entries = LinkedHashMap<String, LoggedNotification>()
    private var file: File? = null
    private var dirty = false

    @Synchronized
    fun bind(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f
        runCatching {
            if (f.isFile) json.decodeFromString<List<LoggedNotification>>(f.readTextAtomically())
                .forEach { entries[it.conversation] = it }
        }.onFailure { Log.w(TAG, "Unreadable notification log; starting over", it) }
    }

    /** Called with every snapshot the listener publishes. */
    @Synchronized
    fun record(items: List<NotificationItem>, now: Long = System.currentTimeMillis()) {
        for (item in NotificationHub.fold(items)) {
            val key = item.packageName + "#" + item.conversationKey.ifBlank { item.key }
            val text = item.thread.takeLast(4).joinToString(" / ").take(MAX_TEXT)
            val old = entries[key]
            if (old != null && old.text == text && old.title == item.title) continue
            entries.remove(key)
            entries[key] = LoggedNotification(key, item.postedAt.takeIf { it > 0 } ?: now, item.appLabel, item.title.take(80), text, old?.hidden == true)
            dirty = true
        }
        trim(now)
        save()
    }

    /** The filter's verdict on a conversation, so the summary leaves it out too. */
    @Synchronized
    fun markHidden(item: NotificationItem) {
        val key = item.packageName + "#" + item.conversationKey.ifBlank { item.key }
        entries[key]?.let { entries[key] = it.copy(hidden = true); dirty = true; save() }
    }

    /** What arrived since [from], oldest first, without what the filter called noise. */
    @Synchronized
    fun since(from: Long): List<LoggedNotification> =
        entries.values.filter { it.at >= from && !it.hidden }.sortedBy { it.at }

    private fun trim(now: Long) {
        val cutoff = now - KEEP_MS
        if (entries.values.removeAll { it.at < cutoff }) dirty = true
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
            dirty = true
        }
    }

    private fun save() {
        if (!dirty) return
        val f = file ?: return
        runCatching { f.writeTextAtomically(json.encodeToString(entries.values.toList())) }
            .onFailure { Log.w(TAG, "Could not save the notification log", it) }
        dirty = false
    }

    const val FILE_NAME = "notification_log.json"
    private const val TAG = "NotificationLog"
    private const val KEEP_MS = 36 * 60 * 60 * 1000L
    private const val MAX_ENTRIES = 300
    private const val MAX_TEXT = 400
}

/**
 * When the person last put the phone down for a while.
 *
 * "While you were away" needs a start, and the only honest one is the screen:
 * the last time it went off and stayed off for [LONG_AWAY_MS] or more. Kept in
 * a small preferences file so a process that died overnight still knows.
 */
object AwayClock {

    private const val PREFS = "away_clock"
    private const val OFF_AT = "off_at"
    private const val LAST_START = "last_start"
    private const val LAST_END = "last_end"
    private const val LONG_AWAY_MS = 30 * 60 * 1000L
    private const val RECENT_MS = 24 * 60 * 60 * 1000L
    private const val FALLBACK_MS = 12 * 60 * 60 * 1000L

    private var registered = false

    fun register(context: Context) {
        if (registered) return
        registered = true
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOff(app)
                    Intent.ACTION_SCREEN_ON -> screenOn(app)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    internal fun screenOff(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(OFF_AT, now).apply()
    }

    internal fun screenOn(context: Context, now: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        val off = p.getLong(OFF_AT, 0L)
        val edit = p.edit().putLong(OFF_AT, 0L)
        if (off > 0 && now - off >= LONG_AWAY_MS) edit.putLong(LAST_START, off).putLong(LAST_END, now)
        edit.apply()
    }

    /**
     * Where "while you were away" begins: the stretch the screen is off for
     * right now (the morning run builds before anyone picks the phone up), or
     * the last long one in the past day, or half a day ago when there was none.
     */
    fun awaySince(context: Context, now: Long = System.currentTimeMillis()): Long {
        val p = prefs(context)
        val off = p.getLong(OFF_AT, 0L)
        if (off > 0 && now - off >= LONG_AWAY_MS) return off
        val start = p.getLong(LAST_START, 0L)
        val end = p.getLong(LAST_END, 0L)
        if (start > 0 && now - end <= RECENT_MS) return start
        return now - FALLBACK_MS
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

package com.aura.launcher.notifications

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.app.RemoteInput
import android.provider.Settings
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One notification, reduced to what a home screen can show.
 *
 * The [StatusBarNotification] itself is not kept. It holds a live binder handle
 * into another process, and a list of them retained across recompositions is a
 * leak with a long fuse — the key is enough to act on later.
 */
data class NotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val clearable: Boolean,
    val contentIntent: PendingIntent?,
    /**
     * The on-device model's rewrite of [text], when post-processing is on
     * and the notification survived the "is this relevant" filter. UI reads
     * [displayText] which falls back to [text] whenever this is null.
     */
    val processedText: String? = null,
    /**
     * The app's own "reply" action, when it published one.
     *
     * A messaging app attaches a `RemoteInput` to the notification so the
     * shade, a watch or a car can answer without opening it. A launcher that
     * already draws the notification can use exactly the same door — and
     * answering from the home screen without loading a chat app is most of
     * what a person does with a message anyway.
     *
     * Null on everything that is not a message, which is most notifications.
     */
    val reply: ReplyAction? = null,
    /**
     * Which thread this belongs to. Two notifications sharing one of these are
     * two messages in the same conversation, and become one card.
     */
    val conversationKey: String = "",
    /**
     * The lines the notification is already carrying, oldest first.
     *
     * A messaging app that says "Joyce: 3 new messages" attaches all three
     * under MessagingStyle. Reading only `text` gave the newest one, which on
     * a message that continues the last one reads as a non sequitur — and gave
     * the model no way to write a line that says what is actually going on.
     */
    val history: List<String> = emptyList(),
    /**
     * The other notifications folded into this card, if any.
     *
     * Held so dismissing the card dismisses all of them: a card that stands
     * for three messages and clears one of them is worse than three cards.
     */
    val mergedKeys: List<String> = emptyList(),
) {
    /** What the launcher actually shows — the rewrite if it landed, else raw. */
    val displayText: String get() = processedText ?: text

    /** True when this notification can be answered in place. */
    val canReply: Boolean get() = reply != null

    /**
     * Everything this card stands for, oldest first, deduplicated.
     *
     * What the model is given, and the thing the cache is keyed on: a new
     * message in a thread changes this, which is exactly when the summary
     * should be written again.
     */
    val thread: List<String>
        get() = (history + text).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** How many notifications this one card is standing for. */
    val foldedCount: Int get() = mergedKeys.size + 1

    /**
     * What this card *says*, as an identity.
     *
     * Everything downstream — the classifier's verdict, the cached rewrite,
     * whether this has been queued before — hangs off this rather than off the
     * notification key, and the difference is the whole point of the change. A
     * messaging app that grows a thread inside one notification keeps the same
     * key while the content changes completely; keying the cache on the key
     * meant the summary written for the first message was still on screen
     * three messages later. The thread changed, so the signature changes, so it
     * is written again.
     */
    val signature: String
        get() = "$conversationKey#${thread.joinToString(" ").hashCode()}"
}

/**
 * Everything needed to answer a notification, and nothing that expires.
 *
 * The `Notification.Action` itself is not kept for the same reason the
 * [StatusBarNotification] is not: it belongs to another process's object graph.
 * What is here is a [PendingIntent], which is designed to be held and fired
 * later, and the name of the field the receiving app expects the text in.
 */
data class ReplyAction(
    val intent: PendingIntent,
    /** `RemoteInput.getResultKey()` — the app decides what it is called. */
    val resultKey: String,
    /** The app's own word for the box: "Reply", "Responder", "Message". */
    val hint: String,
    /**
     * Extras the action was built with, replayed on the intent that fires it.
     *
     * Some apps put the conversation's id in here rather than in the
     * PendingIntent, and firing without them delivers a reply with nowhere to
     * go.
     */
    val extras: Bundle,
    /**
     * Every `RemoteInput` the action published, kept exactly as published.
     *
     * The first version built a fresh one from the result key, on the reasoning
     * that the key is all `addResultsToIntent` reads. That is true of the
     * platform call and not true of the app on the other side: several
     * messaging apps check the descriptor they get back against the one they
     * sent — its label, its choices, its own extras — and drop a reply whose
     * shape they do not recognise, silently, because from their point of view
     * it did not come from their notification.
     *
     * These are parcelable descriptors rather than binder handles, so holding
     * them costs nothing and leaks nothing.
     */
    val inputs: List<RemoteInput>,
)

/**
 * The live notification list, shared between the listener service and the UI.
 *
 * A `NotificationListenerService` is created and destroyed by the system on its
 * own schedule, so nothing that has to outlive it can live inside it. This
 * object is that outside — the service pushes, the launcher reads, and neither
 * needs the other to exist.
 */
object NotificationHub {

    private val _items = MutableStateFlow<List<NotificationItem>>(emptyList())
    val items: StateFlow<List<NotificationItem>> = _items.asStateFlow()

    /** True while the listener is bound; false means the grant is missing. */
    val isConnected: Boolean get() = AuraNotificationListener.instance != null

    /**
     * The last thing the listener published, keyed by notification key. Kept
     * so the pipeline callbacks can decide the visible list from a single
     * source of truth: when a rewrite lands or a classifier says "no", the
     * list is rebuilt from this table plus the caches.
     */
    private val raw = java.util.concurrent.ConcurrentHashMap<String, NotificationItem>()

    /**
     * Rewrites cached per key so the model does not run on every refresh —
     * a group thread that posts every minute would keep hitting the model
     * for lines it has already seen.
     */
    private val rewrites = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Keys the classifier has marked as low signal. These never appear on
     * the launcher's shade — the whole reason for the on-device pass is
     * that promotional noise should be edited out before the user sees it.
     * The set is process-lifetime; a new session gets a clean judgement.
     */
    private val hidden = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * Notifications whose classification has not landed yet. Held out of
     * the visible list until the model has ruled — appearing raw and then
     * disappearing seconds later would be the exact "somem sozinhas" bug
     * the shade was accused of. If nothing is going to happen to a card,
     * it does not appear; when the classifier says YES (or when the
     * pipeline can't decide and gives up), it enters the list already
     * decided.
     */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, NotificationItem>()

    /**
     * Every key we have already handed to the pipeline this session,
     * whatever the outcome was. Without this, a notification the
     * classifier timed out on kept getting re-enqueued on every
     * window-state event — the listener refresh runs on far more triggers
     * than "a new notification arrived", and each pass was rediscovering
     * the same items and pushing them into the model again. Now a key is
     * enqueued at most once per process life: if it timed out or was
     * skipped, that is the answer for the session.
     */
    private val settled = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * Conversations that have already had a card on screen.
     *
     * The hold-until-classified rule is for notifications nobody has seen yet.
     * Applying it to a thread that is already on the home screen would make the
     * card disappear every time another message arrived, which is the opposite
     * of what a card for a live conversation should do.
     */
    private val shown = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * Keys the user has just dismissed but Android may still report active for
     * a moment while [cancelNotification] crosses the listener binder.
     *
     * A refresh in that window used to republish the card. It was especially
     * visible when Android's Back gesture recreated HOME: every card the user
     * had swiped away appeared to come back. A key remains suppressed until an
     * active-notification snapshot confirms it is actually gone; if an app
     * later reuses the key for a new notification, it can appear normally.
     */
    private val dismissed = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    internal fun publish(items: List<NotificationItem>) {
        val activeKeys = items.mapTo(HashSet(items.size)) { it.key }
        // Absence is the listener's acknowledgement that cancellation landed.
        // Iterate the concurrent set explicitly so dismiss and listener
        // callbacks remain safe even if their main-thread ordering changes.
        for (key in dismissed) {
            if (key !in activeKeys) dismissed.remove(key)
        }
        val accepted = items.filter { it.key !in dismissed }

        // Remember the raw shape of everything currently on the shade, so
        // when a classifier verdict arrives later the visible list can be
        // rebuilt from this table + the caches without a fresh binder call.
        raw.clear()
        for (item in accepted) raw[item.key] = item
        // Everything that came in, for the "while you were away" summary —
        // kept even after the shade is cleared.
        runCatching { NotificationLog.record(accepted) }

        rebuild()
    }

    /**
     * Rebuild the visible list from [raw] + the caches.
     *
     * - Items still pending do not appear.
     * - Items the classifier marked as low signal do not appear.
     * - Everything else appears, with the rewrite applied when the model
     *   produced one.
     */
    private fun rebuild() {
        val visible = fold(raw.values.toList())
            .asSequence()
            .filter { !pending.containsKey(it.key) }
            .filter { it.signature !in hidden }
            .map { item ->
                val rewrite = rewrites[item.signature]
                item.copy(processedText = rewrite ?: item.processedText)
            }
            .sortedByDescending { it.postedAt }
            .toList()
        // A conversation that has reached the screen never goes back to being
        // held out of it. See [publish].
        for (item in visible) shown += item.conversationKey
        _items.value = visible
    }

    /**
     * One card per conversation, not one per notification.
     *
     * Some apps update a single notification as a thread grows; others post a
     * fresh one per message. On the second kind the home screen filled up with
     * "Joyce: are you coming", "Joyce: because we start at seven" — two cards,
     * two summaries, and the second one only making sense after the first.
     *
     * The newest notification carries the group, because it owns the reply
     * action and the tap target that are still current. The older ones
     * contribute their lines to [NotificationItem.history] and their keys to
     * [NotificationItem.mergedKeys], so the card can be dismissed as the one
     * thing it appears to be.
     *
     * Anything with no conversation of its own — which is most notifications —
     * has a key unique to itself and falls through this untouched.
     */
    internal fun fold(items: List<NotificationItem>): List<NotificationItem> =
        items.groupBy { it.conversationKey.ifBlank { it.key } }
            .map { (_, group) ->
                if (group.size == 1) return@map group.first()
                val ordered = group.sortedBy { it.postedAt }
                val newest = ordered.last()
                val older = ordered.dropLast(1)
                newest.copy(
                    // Oldest first, so the thread reads in the order it was
                    // written rather than backwards.
                    history = (older.flatMap { it.thread } + newest.history).distinct(),
                    mergedKeys = older.map { it.key },
                    // The card is clearable only if everything under it is;
                    // otherwise a swipe would leave one behind and look broken.
                    clearable = ordered.all { it.clearable },
                )
            }

    /**
     * Called by the pipeline when a rewrite lands. The item enters the
     * visible list at this moment — not before — so a new notification
     * never appears raw and then flips to the rewritten version.
     */
    internal fun applyRewrite(signature: String, key: String, text: String) {
        rewrites[signature] = text
        pending.remove(key)
        trimCaches()
        rebuild()
    }

    /**
     * Called by the pipeline when the classifier marks an item as low
     * signal. Never entered the visible list to begin with; this only
     * commits the decision so the next refresh does not resurrect it.
     */
    internal fun hide(signature: String, key: String) {
        raw[key]?.let { runCatching { NotificationLog.markHidden(it) } }
        hidden += signature
        pending.remove(key)
        rewrites.remove(signature)
        rebuild()
    }

    /**
     * Keeps the signature-keyed caches from growing without end.
     *
     * Keying on content rather than on the notification key bought a correct
     * answer and an unbounded map: every message in a long thread leaves its
     * own entry behind, where the old scheme reused one. A busy day of group
     * chats is thousands of short strings — not much, and not nothing, and
     * this process outlives every one of those conversations.
     */
    private fun trimCaches() {
        if (rewrites.size > CACHE_MAX) {
            val keep = _items.value.map { it.signature }.toSet()
            rewrites.keys.retainAll { it in keep }
        }
        if (settled.size > SETTLED_MAX) settled.clear()
        if (hidden.size > CACHE_MAX) hidden.clear()
    }

    /**
     * Called by the pipeline when the model was unavailable or timed out.
     * The notification is released from pending and appears raw — the
     * honest fallback when we could not enrich it.
     */
    internal fun skipped(key: String) {
        pending.remove(key)
        rebuild()
    }

    internal fun clearAll() {
        _items.value = emptyList()
        raw.clear()
        rewrites.clear()
        hidden.clear()
        pending.clear()
        settled.clear()
        shown.clear()
    }

    /** Dismisses one notification, for both the shade and this list. */
    fun dismiss(item: NotificationItem) {
        // Everything the card stands for, not just the newest of them. A card
        // that says three messages and clears one of them leaves two behind and
        // reads as the swipe having failed.
        val keys = item.mergedKeys + item.key
        // Mark before asking Android to cancel. The listener is free to refresh
        // immediately, and its first snapshot may still contain these keys.
        dismissed.addAll(keys)
        AuraNotificationListener.instance?.let { service ->
            for (k in keys) runCatching { service.cancelNotification(k) }
        }
        // Removed here too rather than waiting for the callback: the swipe that
        // asked for it has already finished animating, and a row that springs
        // back for a frame before disappearing looks like a failure.
        for (k in keys) raw.remove(k)
        rebuild()
    }

    /**
     * Answers a notification in place, through the app's own reply action.
     *
     * The same path the shade uses: put the text into an Intent under the key
     * the app asked for, and fire the PendingIntent it published. The receiving
     * app sends the message; nothing here knows or needs to know how.
     *
     * The card is removed on success rather than waiting for the app to update
     * its own notification. Most messaging apps do replace it a moment later —
     * but "a moment" is long enough that the reply you just sent still sitting
     * there unanswered reads as having failed.
     *
     * @return false when the app has since revoked the intent, which is what a
     *   PendingIntent from a notification that has already been dealt with
     *   elsewhere looks like.
     */
    fun reply(context: Context, item: NotificationItem, text: String): Boolean {
        val action = item.reply
        val message = text.trim()
        if (action == null || message.isEmpty()) {
            NotificationDiagnostics.log("reply", "Nothing to send to ${item.packageName}")
            return false
        }

        val filled = Intent().apply {
            // Replayed so a conversation id the app tucked into the action's
            // extras arrives with the reply rather than being dropped.
            if (!action.extras.isEmpty) putExtras(action.extras)
        }
        // The platform call with the app's own descriptors. The results ride
        // on the intent's ClipData, which `Intent.fillIn` copies because the
        // PendingIntent's own base intent carries none — the same route the
        // notification shade takes.
        RemoteInput.addResultsToIntent(
            action.inputs.toTypedArray(),
            filled,
            Bundle().apply { putCharSequence(action.resultKey, message) },
        )
        // Says "a person typed this", which some apps check before accepting.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            RemoteInput.setResultsSource(filled, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }

        // Logged rather than only returned, because this is the one thing here
        // that cannot be checked from the outside: the message either turns up
        // in the other app or it does not, and when it does not there was
        // nothing anywhere saying whether Aura had even tried.
        val outcome = runCatching { action.intent.send(context, 0, filled) }
        if (outcome.isFailure) {
            val why = outcome.exceptionOrNull()?.message ?: "the app withdrew it"
            Log.w(TAG, "Reply to ${item.packageName} refused", outcome.exceptionOrNull())
            NotificationDiagnostics.log("reply", "${item.packageName} refused the reply: $why")
            return false
        }

        NotificationDiagnostics.log(
            "reply",
            "Sent ${message.length} chars to ${item.packageName} " +
                "(key=${action.resultKey}, ${action.inputs.size} input(s))",
        )
        for (k in item.mergedKeys + item.key) raw.remove(k)
        rebuild()
        return true
    }

    fun dismissAll() {
        // `cancelAllNotifications` has the same asynchronous acknowledgement as
        // cancelling one. Only mark clearable rows: ongoing cards deliberately
        // remain in [raw] below and must not be hidden by a stale tombstone.
        dismissed.addAll(raw.values.filter { it.clearable }.map { it.key })
        AuraNotificationListener.instance?.let { runCatching { it.cancelAllNotifications() } }
        val stayers = raw.values.filterNot { it.clearable }.associateBy { it.key }
        raw.clear()
        raw.putAll(stayers)
        rebuild()
    }

    /**
     * Opens whatever tapping it in the shade would have opened.
     *
     * Two things had to be added before this actually worked.
     *
     * The intent is sent with an explicit background-activity-start grant. It
     * belongs to the app that posted the notification, not to Aura, and from
     * Android 14 the sender has to say out loud that the receiver may start an
     * activity — without that the call succeeds and nothing happens, which is
     * the worst possible failure to debug.
     *
     * And plenty of notifications have no content intent at all: a summary, a
     * media style, anything whose taps live entirely in its actions. Launching
     * the app is the honest answer there rather than a tap that does nothing.
     */
    fun open(context: Context, item: NotificationItem): Boolean {
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                .toBundle()
        } else {
            null
        }

        val sent = item.contentIntent?.let { intent ->
            runCatching { intent.send(context, 0, null, null, null, null, options) }
                .onFailure { Log.w(TAG, "Notification intent refused; opening the app", it) }
                .isSuccess
        } ?: false

        val opened = sent || launchApp(context, item.packageName)
        // Tapping a notification in the shade dismisses it; this should behave
        // the same, or the list disagrees with the app the user just opened.
        if (opened && item.clearable) dismiss(item)
        return opened
    }

    private fun launchApp(context: Context, packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private const val TAG = "NotificationHub"

    /**
     * How many conversation signatures the caches hold before being pruned.
     *
     * Generous: each entry is a short string, and the cost of pruning too
     * eagerly is re-running the model on something it has already judged.
     */
    private const val CACHE_MAX = 400
    private const val SETTLED_MAX = 2_000

    /** Whether the user has granted notification access to Aura. */
    fun hasAccess(context: Context): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            )
        }.getOrNull().orEmpty()
        return enabled.contains(context.packageName)
    }
}

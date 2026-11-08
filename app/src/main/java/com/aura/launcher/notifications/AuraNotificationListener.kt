package com.aura.launcher.notifications

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Watches the notification shade so the launcher can show it.
 *
 * Reading notifications needs a grant of its own, separate from everything else
 * Aura asks for, and the system only ever binds this once the user has given it
 * in Settings. Until then nothing here runs and the home screen simply has no
 * notification section — which is the honest outcome, not a failure.
 *
 * Nothing is stored in this class. The system creates and destroys it on its
 * own schedule, so the list lives in [NotificationHub] and this only pushes.
 */
class AuraNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refresh()
        // Media sessions are readable through this same grant, so the moment it
        // lands is the moment the now-playing card can work. Without this the
        // card would wait for the next resume, which is a visible gap right
        // after the user has turned the permission on.
        com.aura.launcher.media.MediaWatcher.start(applicationContext)
    }

    override fun onListenerDisconnected() {
        instance = null
        NotificationHub.clearAll()
        // The media grant goes with it: keeping a card on screen that can no
        // longer be driven leaves dead buttons on the home screen.
        com.aura.launcher.media.MediaWatcher.stop()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    /**
     * Rebuilds the whole list rather than patching it.
     *
     * `getActiveNotifications` is one binder call and the list is short. Trying
     * to keep an incremental copy in step with grouping, updates and ranking
     * changes is where notification lists go wrong.
     */
    private fun refresh() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return

        val items = active
            .asSequence()
            .filter { it.isUsefulToShow() }
            .sortedByDescending { it.postTime }
            .take(MAX_SHOWN)
            .map { it.toItem() }
            .toList()

        NotificationHub.publish(items)
    }

    private fun StatusBarNotification.isUsefulToShow(): Boolean {
        val n = notification ?: return false
        // Group summaries repeat what their children already say.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // Media transport controls and other ongoing chrome are not messages.
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (n.isMedia()) return false
        if (packageName == applicationContext.packageName) return false

        val extras = n.extras ?: return false
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return title.isNotBlank() || text.isNotBlank()
    }

    private fun StatusBarNotification.toItem(): NotificationItem {
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        val label = runCatching {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)

        return NotificationItem(
            key = key,
            packageName = packageName,
            appLabel = label,
            title = title.trim(),
            text = text.trim(),
            postedAt = postTime,
            clearable = isClearable,
            contentIntent = notification.contentIntent,
            reply = replyAction(),
            conversationKey = conversationKey(),
            history = messageLines(),
        )
    }

    /**
     * What thread this belongs to, so two messages from the same person are
     * one thing on the home screen rather than two.
     *
     * In descending order of how well it survives: the shortcut id, which is
     * what a conversation-aware app publishes precisely to say "this is that
     * chat"; the conversation title, which MessagingStyle carries for groups;
     * the notification's own title, which for a messaging app is the sender's
     * name; and finally the key, which makes it its own conversation of one —
     * the right answer for everything that is not a chat.
     */
    private fun StatusBarNotification.conversationKey(): String {
        val extras = notification?.extras
        val shortcut = runCatching { notification?.shortcutId }.getOrNull()?.takeIf { it.isNotBlank() }
        val conversation = extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return "$packageName|" + (shortcut ?: conversation ?: title ?: key)
    }

    /**
     * Every line the notification is already carrying, oldest first.
     *
     * This is the part that was being thrown away. A messaging app that shows
     * "Joyce: 3 new messages" attaches all three to the notification under
     * MessagingStyle — the shade expands to show them — and Aura was reading
     * only `EXTRA_TEXT`, which is the last one. So the summary said what the
     * newest message said, and a second line that only made sense after the
     * first read as a non sequitur.
     *
     * Three shapes, because apps use all three: MessagingStyle's message
     * array, InboxStyle's text lines, and BigTextStyle's single long body.
     * Empty for the ordinary notification that has nothing but its own text,
     * which the caller already has.
     */
    private fun StatusBarNotification.messageLines(): List<String> {
        val extras = notification?.extras ?: return emptyList()

        val messages = runCatching {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }.getOrNull()
        if (messages != null && messages.isNotEmpty()) {
            val lines = messages.mapNotNull { parcel ->
                val bundle = parcel as? Bundle ?: return@mapNotNull null
                val body = bundle.getCharSequence(MESSAGE_TEXT)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val who = bundle.getCharSequence(MESSAGE_SENDER)?.toString()?.trim()
                if (who.isNullOrEmpty()) body else "$who: $body"
            }
            if (lines.isNotEmpty()) return lines.takeLast(MAX_HISTORY)
        }

        val inbox = runCatching {
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        }.getOrNull()
        if (inbox != null && inbox.isNotEmpty()) {
            return inbox.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .takeLast(MAX_HISTORY)
        }

        // Only worth returning when it says more than the short text already
        // does — otherwise this is the same line twice.
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val short = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        return if (!big.isNullOrEmpty() && big != short) listOf(big) else emptyList()
    }

    /**
     * The app's own reply box, if it published one.
     *
     * A messaging app attaches a `RemoteInput` to one of its actions so the
     * shade can answer without opening it, and there is nothing shade-specific
     * about that door — anything holding the notification may use it.
     *
     * The first action with a text-allowing RemoteInput wins. Apps that also
     * attach choice-only inputs ("Yes"/"No" chips on a watch) are skipped for
     * those: `allowFreeFormInput` is false there, and typing into one produces
     * nothing the app will read.
     */
    private fun StatusBarNotification.replyAction(): ReplyAction? {
        val actions = notification?.actions ?: return null
        for (action in actions) {
            val intent = action.actionIntent ?: continue
            val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: continue
            val key = input.resultKey ?: continue
            return ReplyAction(
                intent = intent,
                resultKey = key,
                hint = (input.label ?: action.title)?.toString()?.trim().orEmpty(),
                extras = action.extras ?: Bundle(),
                // Every input the action carries, not just the one being typed
                // into: `addResultsToIntent` writes the whole set back, and an
                // app that published a free-form box alongside choice chips
                // expects to get both descriptors returned.
                inputs = action.remoteInputs.orEmpty().toList(),
            )
        }
        return null
    }

    companion object {
        /**
         * Set while the system has this bound. Written from the service's own
         * thread and read from the launcher's, hence @Volatile.
         */
        @Volatile
        internal var instance: AuraNotificationListener? = null
            private set

        /** A home screen is not a shade; past a handful this stops helping. */
        private const val MAX_SHOWN = 8

        /**
         * How much of a thread is carried forward.
         *
         * Enough to make the newest message make sense, and short enough that a
         * group chat of forty messages does not become the prompt. The most
         * recent are the ones kept — a summary is about what is happening now.
         */
        private const val MAX_HISTORY = 6

        /**
         * The keys MessagingStyle packs each message under.
         *
         * Read by name rather than through `Message.getMessagesFromBundleArray`,
         * which is not something to depend on from outside the framework. These
         * two strings are part of the notification's on-the-wire shape and have
         * not moved in a decade.
         */
        private const val MESSAGE_TEXT = "text"
        private const val MESSAGE_SENDER = "sender"
    }
}

/**
 * A player's transport notification, which the home screen already draws
 * as the now-playing widget.
 *
 * The ongoing flag was supposed to catch these and does not: YouTube Music
 * posts a dismissible media notification, so the same track appeared twice
 * on the home screen — once as the widget, once as a card underneath it
 * saying exactly the same words.
 *
 * Three tests because no single one holds everywhere. EXTRA_MEDIA_SESSION
 * is what MediaStyle actually attaches and is the strongest signal;
 * EXTRA_TEMPLATE names the style for anything built through the platform
 * builder; CATEGORY_TRANSPORT catches players that hand-roll their layout.
 *
 * Filtered here rather than downstream on purpose. This runs before
 * anything is published, so a media notification never reaches the
 * classifier — there is nothing for the model to decide about a card the
 * launcher was never going to show, and running it would spend a wake lock
 * and a second of inference to reach that conclusion.
 */
internal fun Notification.isMedia(): Boolean {
    if (category == Notification.CATEGORY_TRANSPORT) return true
    val extras = extras ?: return false
    if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true
    return extras.getString(Notification.EXTRA_TEMPLATE)?.endsWith("MediaStyle") == true
}

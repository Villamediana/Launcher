package com.aura.launcher.media

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What is playing on the phone right now, reduced to what a home screen shows.
 *
 * Deliberately not a `MediaController`. That holds a live binder into the
 * player's process, and one retained across recompositions is a leak with a long
 * fuse — the same reason [com.aura.launcher.notifications.NotificationItem]
 * keeps a key rather than a `StatusBarNotification`. Commands go back out
 * through [MediaWatcher], which owns the controller and outlives any screen.
 */
data class NowPlaying(
    val packageName: String,
    /** The app's own name, for the one line that says where this is coming from. */
    val appLabel: String,
    val title: String,
    /**
     * The artist, or for video the channel — the session's own subtitle, whatever
     * the player chose to put there. Blank is normal and the card copes.
     */
    val subtitle: String,
    val isPlaying: Boolean,
    /** Album art or video thumbnail, already copied out of the session. */
    val artwork: Bitmap? = null,
    /**
     * True once the session is gone but the card is still up.
     *
     * A player that has been closed leaves nothing to drive, so the controls go
     * quiet rather than staying lit and doing nothing — but the card itself is
     * not yanked out from under the user mid-glance. It goes on the next sweep.
     */
    val isStale: Boolean = false,
    /**
     * Whether this session looks like video rather than music.
     *
     * Changes what the card says, not what it does: a YouTube session gets
     * "Watching" instead of a headphone glyph, and its thumbnail is drawn wide
     * rather than square, because a 16:9 frame cropped to a square is the one
     * thing that makes a video card look broken.
     */
    val isVideo: Boolean = false,
)

/**
 * The live now-playing state, shared between the watcher and the UI.
 *
 * Separate object for the same reason [com.aura.launcher.notifications.NotificationHub]
 * is: the thing that produces this hangs off a `NotificationListenerService`,
 * which the system creates and destroys on its own schedule, and the home screen
 * must not depend on that timing.
 */
object MediaHub {

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    internal fun publish(state: NowPlaying?) {
        _nowPlaying.value = state
    }

    internal fun clear() {
        _nowPlaying.value = null
    }
}

package com.aura.launcher.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.aura.launcher.notifications.AuraNotificationListener

/**
 * Watches Android's media sessions so the home screen can show what is playing.
 *
 * Rides on a grant the launcher already asks for. `getActiveSessions` is gated
 * on being a bound notification listener — the same switch that turns on the
 * notification section — so showing a now-playing card costs the user nothing
 * they have not already given, and asks for no new permission. Without that
 * grant this simply never produces anything, which is the honest outcome rather
 * than a failure.
 *
 * One controller at a time. A phone can have several sessions alive — a paused
 * podcast, a browser tab, a game's ambient loop — and a home screen showing all
 * of them is a mixing desk. [pickBest] chooses the one the user would mean.
 */
object MediaWatcher {

    private var manager: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null
    private val main = Handler(Looper.getMainLooper())

    /** The session being shown, and the callback registered on it. */
    private var controller: MediaController? = null
    private var callback: MediaController.Callback? = null

    private var appContext: Context? = null

    // ------------------------------------------------- keeping it on screen --

    /**
     * Taking the card down, delayed, so one callback cannot do it.
     *
     * Players do not go from one track to the next in a single step, and video
     * is the worst of it: press next on a YouTube video and the session can be
     * torn down entirely while the next one opens, so the honest answer to
     * "what is playing" really is *nothing* for a second or two. Answering it
     * immediately is a card that vanishes when you press its own button and
     * comes back when the next video starts — which is what this looked like on
     * YouTube and never on Spotify, because Spotify keeps one session for the
     * life of the app.
     *
     * So going away waits and coming back does not: anything worth showing
     * cancels this on arrival. A player that has genuinely been closed still
     * loses its card, a few seconds later, which is the right way round for a
     * mistake to be made.
     */
    private val takeCardDown = Runnable {
        MediaHub.clear()
        forgetArtwork()
    }

    /** Dimming the controls of a session that has gone, on the same principle. */
    private val markCardStale = Runnable {
        MediaHub.nowPlaying.value?.let { MediaHub.publish(it.copy(isStale = true)) }
    }

    private fun clearSoon() {
        main.removeCallbacks(takeCardDown)
        main.postDelayed(takeCardDown, EMPTY_GRACE_MS)
    }

    private fun clearNow() {
        main.removeCallbacks(takeCardDown)
        main.removeCallbacks(markCardStale)
        MediaHub.clear()
        forgetArtwork()
    }

    private fun show(now: NowPlaying) {
        main.removeCallbacks(takeCardDown)
        main.removeCallbacks(markCardStale)
        MediaHub.publish(now)
    }

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attachTo(pickBest(controllers.orEmpty()))
        }

    /**
     * Starts watching, or does nothing if the grant is missing.
     *
     * Safe to call repeatedly — the launcher calls it on every resume, because
     * the user may have granted the listener while away in system settings and
     * there is no callback for "a permission you asked for was just given".
     */
    fun start(context: Context) {
        appContext = context.applicationContext
        if (manager != null) {
            // Already running; just re-read in case sessions changed while the
            // listener was rebinding.
            refresh()
            return
        }

        val svc = context.applicationContext
            .getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val component = ComponentName(context.applicationContext, AuraNotificationListener::class.java)

        // Throws SecurityException until the notification listener is granted,
        // which is the normal state for anyone who has not turned it on.
        val ok = runCatching {
            svc.addOnActiveSessionsChangedListener(sessionsChanged, component, main)
            attachTo(pickBest(svc.getActiveSessions(component)))
        }.isSuccess

        if (!ok) {
            runCatching { svc.removeOnActiveSessionsChangedListener(sessionsChanged) }
            clearNow()
            return
        }

        manager = svc
        listenerComponent = component
    }

    fun stop() {
        manager?.let { svc ->
            runCatching { svc.removeOnActiveSessionsChangedListener(sessionsChanged) }
        }
        manager = null
        listenerComponent = null
        detach()
        clearNow()
    }

    /** Re-reads the session list without re-registering anything. */
    fun refresh() {
        val svc = manager ?: return
        val component = listenerComponent ?: return
        runCatching { attachTo(pickBest(svc.getActiveSessions(component))) }
    }

    // ------------------------------------------------------------- commands --

    fun playPause() = withControls { controls, playing ->
        if (playing) controls.pause() else controls.play()
    }

    fun skipNext() = withControls { controls, _ -> controls.skipToNext() }

    fun skipPrevious() = withControls { controls, _ -> controls.skipToPrevious() }

    /**
     * Throws the current card away without touching playback.
     *
     * The session is remembered rather than the card simply being blanked,
     * because the player keeps publishing updates and the next one would put it
     * straight back — which is what "dismiss" doing nothing looks like. It
     * comes back on its own terms: a different player, or this one starting
     * again after being stopped.
     */
    fun dismissCurrent() {
        dismissedToken = controller?.sessionToken
        // Immediately. The grace elsewhere is for a player that might still be
        // mid-thought; this one is the user having said no.
        clearNow()
    }

    private var dismissedToken: Any? = null

    private inline fun withControls(block: (MediaController.TransportControls, Boolean) -> Unit) {
        val c = controller ?: return
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        runCatching { block(c.transportControls, playing) }
            .onFailure { Log.w(TAG, "Transport command failed", it) }
        // The player answers through the callback, but some are slow about it
        // and a button that does nothing for half a second feels broken.
        main.postDelayed({ republish() }, OPTIMISTIC_REFRESH_MS)
    }

    /** Brings the player's own app forward, for a tap on the card. */
    fun openPlayer(context: Context) {
        val pkg = MediaHub.nowPlaying.value?.packageName ?: return
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg)
        }.getOrNull() ?: return
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // -------------------------------------------------------------- session --

    /**
     * Which of several live sessions the user means.
     *
     * Anything actually playing wins, because that is the sound in the room. Of
     * the rest, the most recently active paused session — a podcast paused two
     * minutes ago is worth a card; one paused yesterday is clutter, and the
     * system drops those sessions on its own soon enough.
     */
    internal fun pickBest(controllers: List<MediaController>): MediaController? {
        val index = pickBestIndex(
            controllers.map { it.playbackState?.state to (it.playbackState?.lastPositionUpdateTime ?: 0L) }
        )
        return index?.let { controllers[it] }
    }

    /**
     * The rule itself, over just the two things it reads.
     *
     * Split out from [pickBest] so it can be tested: a `MediaController` cannot
     * be built without a live session on a real device, and the decision — not
     * the plumbing that fetches it — is the part with a bug in it.
     */
    internal fun pickBestIndex(sessions: List<Pair<Int?, Long>>): Int? {
        if (sessions.isEmpty()) return null
        val playing = sessions.withIndex().filter { it.value.first == PlaybackState.STATE_PLAYING }
        if (playing.isNotEmpty()) return playing.maxByOrNull { it.value.second }?.index
        // A session mid-skip outranks one sitting paused. Without this, pressing
        // next on the card can hand the card to a different app for the second
        // or two it takes the first to land on the next track — the player you
        // are using losing to the one you are not, at the exact moment you
        // touched it.
        val moving = sessions.withIndex().filter { it.value.first.isTransitional() }
        if (moving.isNotEmpty()) return moving.maxByOrNull { it.value.second }?.index
        return sessions.withIndex()
            .filter { it.value.first.isWorthShowing() }
            .maxByOrNull { it.value.second }
            ?.index
    }

    /**
     * A transport operation in progress, rather than somewhere playback rests.
     *
     * These are the states a player passes *through*, and the card has to sit
     * still for all of them. Pressing next on YouTube puts the session into
     * SKIPPING_TO_NEXT for as long as the next video takes to open, and treating
     * that as nothing to show is what made the card disappear for a few seconds
     * and come back — the control vanishing in response to being used.
     */
    internal fun Int?.isTransitional(): Boolean = when (this) {
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        -> true
        else -> false
    }

    /**
     * States a card should stay up for.
     *
     * Paused is emphatically one of them. The first cut treated pausing as
     * "nothing to show" and the card vanished the moment the music stopped,
     * which is precisely backwards: a paused track is the one you are most
     * likely to want to restart, and a control that disappears when you use it
     * is not a control. So is everything in [isTransitional], for the same
     * reason one layer along.
     *
     * Stopped is not, and used to be. It is where a player parks on the way
     * out — close YouTube and its session can sit there, alive and stopped, for
     * as long as Android keeps the process — and a card for it stayed on the
     * home screen the whole time, with nothing behind it. It was allowed
     * because players also pass through stopped between tracks, which is now
     * covered by the delay on taking the card down: a stop that is really a
     * gap gets its session back within a second or two and the card never
     * moves.
     */
    internal fun Int?.isWorthShowing(): Boolean = when (this) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_PAUSED,
        -> true
        else -> isTransitional()
    }

    private fun attachTo(next: MediaController?) {
        // A card the user threw away stays away while that same session is
        // still the best one going. Anything else — a different player, or
        // this one waking up again — clears the memo.
        if (next != null && next.sessionToken == dismissedToken) {
            if (next.playbackState?.state == PlaybackState.STATE_PLAYING) {
                dismissedToken = null
            } else {
                return
            }
        } else if (next?.sessionToken != dismissedToken) {
            dismissedToken = null
        }

        if (next?.sessionToken == controller?.sessionToken) {
            republish()
            return
        }
        detach()
        if (next == null) {
            clearSoon()
            return
        }
        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = republish()
            override fun onMetadataChanged(metadata: MediaMetadata?) = republish()
            override fun onSessionDestroyed() {
                // The player closed — or it is a video app building the session
                // for the next item, which looks identical from here. Look for a
                // live session first; only if none turns up do the controls go
                // quiet, and only after that does the card go.
                //
                // Dimming first was the visible half of the flicker: every skip
                // greyed the buttons for the beat before the new session landed.
                detach()
                main.removeCallbacks(markCardStale)
                main.postDelayed(markCardStale, STALE_GRACE_MS)
                refresh()
            }
        }
        runCatching { next.registerCallback(cb, main) }
            .onFailure { Log.w(TAG, "Could not watch the session", it); return }
        controller = next
        callback = cb
        republish()
    }

    private fun detach() {
        val c = controller
        val cb = callback
        if (c != null && cb != null) runCatching { c.unregisterCallback(cb) }
        controller = null
        callback = null
    }

    private fun republish() {
        val c = controller ?: return clearSoon()
        val context = appContext ?: return
        val state = c.playbackState
        if (!state?.state.isWorthShowing()) {
            clearSoon()
            return
        }
        val next = snapshot(c, context)
        // The blank frame in the middle of a skip. Publishing it is a card that
        // loses its picture and its title, says "YouTube", and then fills back
        // in a second later — which is the flicker, and it is not fixable by
        // holding the card still, because the card is exactly what changed.
        if (isEmptierThan(next, MediaHub.nowPlaying.value)) return
        show(next)
    }

    /**
     * Whether this reading of the session says less than what is already up.
     *
     * A player clears its metadata before it sets the next track's, so there is
     * a moment where the truth about the session is genuinely nothing — no
     * title, no artwork, just the app's own name. That moment is real and it is
     * also useless: nobody wants a card that empties itself and refills.
     *
     * Only ever within one player. A different app's session saying nothing yet
     * is a new card, and holding the old one there would be attributing one
     * app's track to another.
     */
    internal fun isEmptierThan(next: NowPlaying, current: NowPlaying?): Boolean {
        if (current == null || current.packageName != next.packageName) return false
        // The snapshot falls back to the app's label when the metadata carries
        // no title, so this is "the player told us nothing".
        return next.title == next.appLabel && current.title != current.appLabel
    }

    /**
     * Copies everything the card needs out of the session, so nothing that
     * reaches the UI still points into the player's process.
     */
    private fun snapshot(c: MediaController, context: Context): NowPlaying {
        val meta = c.metadata
        val state = c.playbackState
        val actions = state?.actions ?: 0L

        val title = meta?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString().orEmpty().trim()
        val artist = listOf(
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_AUTHOR,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        ).firstNotNullOfOrNull { key ->
            meta?.getText(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()

        val artwork = artworkFor(
            track = "${c.packageName} $title $artist",
            source = listOf(
                MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON,
            ).firstNotNullOfOrNull { key -> meta?.getBitmap(key) },
        )

        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)

        return NowPlaying(
            packageName = c.packageName,
            appLabel = label,
            title = title.ifBlank { label },
            subtitle = artist,
            // An in-between state does not change whether it is playing, so the
            // button keeps whatever it last meant. Otherwise pressing next
            // flips the pause glyph to play and back again while the next track
            // loads, which reads as the track having been stopped by the press.
            isPlaying = playingThrough(state?.state, c.packageName),
            artwork = artwork?.let { safeCopy(it) },
            // Nothing here consults the player's action mask. It is published
            // inconsistently — some leave it empty and queue fine, some list
            // only what is possible *this instant* so previous vanishes on the
            // first track of an album, and some never set it while responding
            // to the commands perfectly. Hiding a button on that evidence is
            // what once left this with nothing but play/pause.
            isVideo = looksLikeVideo(artwork),
        )
    }

    /** The picture the card is already drawing, and the track it belongs to. */
    private var artTrack: String? = null
    private var artCopy: Bitmap? = null

    /**
     * The same track's artwork as the same object, every time.
     *
     * This is the other half of the flicker, and the less obvious one. A player
     * sends several callbacks around a single change — metadata, then state,
     * then state again — and each one rebuilt the snapshot, which copied the
     * bitmap afresh. A new object is a different object: the card's palette
     * effect is keyed on the artwork, so it re-derived the accent colour and
     * animated to it from scratch on every callback, and a state flow holding a
     * bitmap can never skip a duplicate emission because bitmaps compare by
     * identity. Pause and play a track and the whole card would blink its way
     * back to normal.
     *
     * Keyed on the track rather than on the bitmap because the bitmap arrives
     * across a binder and is a new instance each time it is read. Artwork that
     * loads late still lands: the cache is only trusted once it holds something.
     */
    private fun artworkFor(track: String, source: Bitmap?): Bitmap? {
        if (track == artTrack && (artCopy != null || source == null)) return artCopy
        artTrack = track
        artCopy = source?.let { safeCopy(it) }
        return artCopy
    }

    private fun forgetArtwork() {
        artTrack = null
        artCopy = null
    }

    /**
     * Whether to draw this as playing, holding steady through a transition.
     *
     * Only ever carried over from the same player: a different app's card
     * inheriting this one's pause glyph would be a button lying about a session
     * it has never seen.
     */
    private fun playingThrough(state: Int?, packageName: String): Boolean = when {
        state == PlaybackState.STATE_PLAYING -> true
        state.isTransitional() -> MediaHub.nowPlaying.value
            ?.takeIf { it.packageName == packageName }
            ?.isPlaying == true
        else -> false
    }

    /**
     * A 16:9-ish frame is a video; a square is a record sleeve.
     *
     * Guessing from the artwork's shape rather than from a package allowlist,
     * because a list of "video apps" is a list of the ones its author thought
     * of — the same reasoning the app search uses for why it asks the model
     * instead of keeping a synonym table.
     */
    internal fun looksLikeVideo(art: Bitmap?): Boolean {
        if (art == null) return false
        if (art.height == 0) return false
        return art.width.toFloat() / art.height >= WIDE_ASPECT
    }

    /**
     * Hardware bitmaps cannot be read by Compose's software drawing on every
     * path, and the player may recycle the one it handed over. A copy is a few
     * hundred kilobytes and removes both problems.
     */
    private fun safeCopy(source: Bitmap): Bitmap? = runCatching {
        if (source.isRecycled) return null
        source.copy(Bitmap.Config.ARGB_8888, false) ?: source
    }.getOrNull()

    private const val TAG = "MediaWatcher"
    private const val OPTIMISTIC_REFRESH_MS = 350L

    /**
     * How long the card waits before believing there is nothing to show.
     *
     * Long enough to cover a video app tearing its session down and building
     * the next one, which is seconds rather than milliseconds and is the whole
     * reason this exists. Short enough that a player the user actually closed
     * does not leave a card sitting on the home screen: five seconds is about
     * as long as a wrong answer here is tolerable.
     */
    private const val EMPTY_GRACE_MS = 5_000L

    /** And before believing its controls have stopped working. */
    private const val STALE_GRACE_MS = 1_200L

    private const val WIDE_ASPECT = 1.45f
}

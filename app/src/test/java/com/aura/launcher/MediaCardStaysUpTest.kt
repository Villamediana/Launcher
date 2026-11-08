package com.aura.launcher

import android.media.session.PlaybackState
import com.aura.launcher.media.MediaWatcher
import com.aura.launcher.media.NowPlaying
import com.aura.launcher.media.MediaWatcher.isTransitional
import com.aura.launcher.media.MediaWatcher.isWorthShowing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the now-playing card survives being used.
 *
 * Pressing next does not take a player straight from one track to the next: it
 * goes through SKIPPING_TO_NEXT, and on video that can last seconds while the
 * next item opens. Every one of those states used to read as "nothing is
 * playing", so the card cleared itself the moment it was touched and came back
 * when the next track started — a control that disappears in response to being
 * pressed, which reads as the press having broken something.
 *
 * These are plain integers from the platform, so the rule can be pinned without
 * a device, a player, or a live session.
 */
class MediaCardStaysUpTest {

    /** Every state a transport operation passes through. */
    private val transitional = listOf(
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
    )

    @Test
    fun `the card stays up through every kind of skip`() {
        transitional.forEach { state ->
            assertTrue("state $state cleared the card", state.isWorthShowing())
        }
    }

    @Test
    fun `resting states are still worth showing`() {
        // Paused most of all: it is the track you are most likely to restart.
        listOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_PAUSED,
        ).forEach { assertTrue("state $it lost the card", it.isWorthShowing()) }
    }

    @Test
    fun `nothing playing is still nothing`() {
        // The card has to be able to go away, or a phone that has not played
        // anything since boot shows a dead player on the home screen.
        assertFalse(PlaybackState.STATE_NONE.isWorthShowing())
        assertFalse(PlaybackState.STATE_ERROR.isWorthShowing())
        assertFalse(null.isWorthShowing())
    }

    @Test
    fun `a stopped session does not keep the card for ever`() {
        // Where a player parks on the way out. Close YouTube and its session
        // can sit there, alive and stopped, for as long as Android keeps the
        // process — and the card sat on the home screen with nothing behind it
        // until something else happened to disturb it.
        //
        // The gap between two tracks also passes through stopped, which is why
        // this used to be allowed. That case is covered a layer up now: taking
        // the card down waits, and a stop that is really a gap has its session
        // back long before the wait is over.
        assertFalse(PlaybackState.STATE_STOPPED.isWorthShowing())
    }

    @Test
    fun `a resting state is not a transition`() {
        listOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_ERROR,
        ).forEach { assertFalse("state $it read as a transition", it.isTransitional()) }
        assertFalse(null.isTransitional())
    }

    // ------------------------------------------------------ which session --

    @Test
    fun `the session being skipped keeps the card, not the paused one`() {
        // Two players alive, and the one the thumb just touched is mid-skip.
        // Handing the card to the other app at that instant is the same flicker
        // one layer up: the card does not vanish, it becomes somebody else's.
        val sessions = listOf(
            PlaybackState.STATE_PAUSED to 900L,
            PlaybackState.STATE_SKIPPING_TO_NEXT to 100L,
        )
        assertEquals(1, MediaWatcher.pickBestIndex(sessions))
    }

    @Test
    fun `something actually playing still wins over a skip`() {
        val sessions = listOf(
            PlaybackState.STATE_SKIPPING_TO_NEXT to 900L,
            PlaybackState.STATE_PLAYING to 100L,
        )
        assertEquals(1, MediaWatcher.pickBestIndex(sessions))
    }

    @Test
    fun `the most recent of several skips is the one shown`() {
        val sessions = listOf(
            PlaybackState.STATE_BUFFERING to 100L,
            PlaybackState.STATE_SKIPPING_TO_NEXT to 900L,
        )
        assertEquals(1, MediaWatcher.pickBestIndex(sessions))
    }

    @Test
    fun `a paused session is still shown when nothing else is going`() {
        val sessions = listOf(
            PlaybackState.STATE_NONE to 900L,
            PlaybackState.STATE_PAUSED to 100L,
        )
        assertEquals(1, MediaWatcher.pickBestIndex(sessions))
    }

    @Test
    fun `no session worth showing means no card`() {
        assertEquals(null, MediaWatcher.pickBestIndex(emptyList()))
        assertEquals(
            null,
            MediaWatcher.pickBestIndex(listOf(PlaybackState.STATE_NONE to 1L, null to 2L)),
        )
    }

    // -------------------------------------------- the blank frame mid-skip --

    private fun card(pkg: String, title: String, label: String = "YouTube") =
        NowPlaying(packageName = pkg, appLabel = label, title = title, subtitle = "", isPlaying = true)

    @Test
    fun `the empty moment between two videos does not replace the card`() {
        // A player clears its metadata before setting the next track's, so for
        // a moment the session honestly says nothing: no title, no artwork,
        // just the app's name. Publishing that is a card that empties itself
        // and refills a second later, which is the flicker.
        val current = card("com.google.android.youtube", "Fable 5.1 Vs ChatGPT")
        val blank = card("com.google.android.youtube", "YouTube")
        assertTrue(MediaWatcher.isEmptierThan(blank, current))
    }

    @Test
    fun `a real title always replaces whatever is up`() {
        val current = card("com.google.android.youtube", "Fable 5.1 Vs ChatGPT")
        val next = card("com.google.android.youtube", "The next video")
        assertFalse(MediaWatcher.isEmptierThan(next, current))
    }

    @Test
    fun `another app saying nothing yet is still its turn`() {
        // Holding YouTube's card up over a Spotify session would be one app's
        // track shown under another app's name.
        val current = card("com.google.android.youtube", "Fable 5.1 Vs ChatGPT")
        val blank = card("com.spotify.music", "Spotify", label = "Spotify")
        assertFalse(MediaWatcher.isEmptierThan(blank, current))
    }

    @Test
    fun `the first card of all is never held back`() {
        assertFalse(MediaWatcher.isEmptierThan(card("com.spotify.music", "Spotify"), null))
    }

    @Test
    fun `two empty readings in a row do not deadlock the card`() {
        // Once what is up is itself titleless there is nothing left to protect,
        // so the next reading gets through whatever it says.
        val blank = card("com.google.android.youtube", "YouTube")
        assertFalse(MediaWatcher.isEmptierThan(blank, blank))
    }
}

package com.aura.launcher

import android.graphics.Bitmap
import android.media.session.PlaybackState
import com.aura.launcher.media.MediaWatcher
import com.aura.launcher.media.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The now-playing widget's real logic: which session to show when the phone has
 * several alive at once, and whether what is playing is a record or a video.
 *
 * The playhead maths that used to live here went with the progress bar. Nothing
 * extrapolates a position any more, so there is nothing left to get wrong about
 * it — and a test for code that no longer runs is worse than no test.
 */
@RunWith(RobolectricTestRunner::class)
class NowPlayingTest {

    private fun track(isPlaying: Boolean = true) = NowPlaying(
        packageName = "com.example.player",
        appLabel = "Player",
        title = "Those Eyes",
        subtitle = "New West",
        isPlaying = isPlaying,
    )

    // ------------------------------------------------------ which session --

    @Test
    fun `no sessions at all shows no card`() {
        assertNull(MediaWatcher.pickBestIndex(emptyList()))
        assertNull(
            MediaWatcher.pickBestIndex(
                listOf(PlaybackState.STATE_ERROR to 20L, PlaybackState.STATE_NONE to 30L)
            )
        )
    }

    @Test
    fun `pausing keeps the card`() {
        // The card used to vanish the moment the music stopped, which is
        // backwards: a paused track is the one most likely to be restarted,
        // and a control that disappears when used is not a control.
        assertEquals(0, MediaWatcher.pickBestIndex(listOf(PlaybackState.STATE_PAUSED to 5L)))
    }

    @Test
    fun `a stopped player loses its card`() {
        // This used to be the opposite, on the grounds that players park here
        // between tracks — but they also park here on the way out, and a
        // session can sit stopped and alive for as long as Android keeps the
        // process. Close YouTube and the card stayed on the home screen with
        // nothing behind it, until something unrelated happened to disturb it.
        //
        // The between-tracks case it was protecting is now covered a layer up,
        // where taking the card down waits a few seconds: a stop that is really
        // a gap has its session back long before the wait is over, and the card
        // never moves. See MediaCardStaysUpTest.
        assertNull(MediaWatcher.pickBestIndex(listOf(PlaybackState.STATE_STOPPED to 5L)))
    }

    @Test
    fun `the sound in the room wins over anything paused`() {
        // Even when the paused session was touched more recently.
        val index = MediaWatcher.pickBestIndex(
            listOf(
                PlaybackState.STATE_PAUSED to 900L,
                PlaybackState.STATE_PLAYING to 100L,
            )
        )
        assertEquals(1, index)
    }

    @Test
    fun `between two playing sessions the most recent wins`() {
        val index = MediaWatcher.pickBestIndex(
            listOf(
                PlaybackState.STATE_PLAYING to 100L,
                PlaybackState.STATE_PLAYING to 900L,
            )
        )
        assertEquals(1, index)
    }

    @Test
    fun `with nothing playing the most recently touched session is offered`() {
        val index = MediaWatcher.pickBestIndex(
            listOf(
                PlaybackState.STATE_PAUSED to 100L,
                PlaybackState.STATE_PAUSED to 999L,
                PlaybackState.STATE_PAUSED to 500L,
            )
        )
        assertEquals(1, index)
    }

    @Test
    fun `an errored session never wins over a usable one`() {
        val index = MediaWatcher.pickBestIndex(
            listOf(
                PlaybackState.STATE_ERROR to 999L,
                PlaybackState.STATE_PAUSED to 100L,
            )
        )
        assertEquals(1, index)
    }

    @Test
    fun `buffering counts as worth showing`() {
        // The gap between tapping play and the first sound is exactly when the
        // user is looking at the card.
        assertEquals(0, MediaWatcher.pickBestIndex(listOf(PlaybackState.STATE_BUFFERING to 1L)))
    }

    // ------------------------------------------------------ video or music --

    @Test
    fun `a wide frame is video and a square is a record sleeve`() {
        val wide = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val square = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        assertTrue(MediaWatcher.looksLikeVideo(wide))
        assertFalse(MediaWatcher.looksLikeVideo(square))
    }

    // ---------------------------------------------- a card you can still use --

    @Test
    fun `a closed player leaves the card up but not drivable`() {
        // The session is gone, so the buttons have nothing to talk to — but the
        // card is not yanked out from under the user mid-glance.
        val gone = track().copy(isStale = true)
        assertTrue("the card still has something to show", gone.title.isNotBlank())
        assertTrue(gone.isStale)
    }

    @Test
    fun `no artwork is not video`() {
        // Plenty of music apps publish none; defaulting to a wide video frame
        // would give them the wrong card.
        assertFalse(MediaWatcher.looksLikeVideo(null))
    }
}

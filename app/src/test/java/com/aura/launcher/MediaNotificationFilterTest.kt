package com.aura.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.aura.launcher.notifications.isMedia
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A player's transport notification must never reach the home screen list.
 *
 * The now-playing widget already draws it, so leaving it in the shade copy put
 * "Is This Love (Kaya 40 Mix)" on screen twice — once as the widget, once as a
 * card directly underneath saying the same words. The ongoing flag was supposed
 * to catch it and does not: YouTube Music posts a dismissible one.
 */
@RunWith(RobolectricTestRunner::class)
class MediaNotificationFilterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("test", "test", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun build(block: Notification.Builder.() -> Unit): Notification =
        Notification.Builder(context, "test")
            .setContentTitle("Is This Love (Kaya 40 Mix)")
            .setContentText("Bob Marley e The Wailers")
            .apply(block)
            .build()

    @Test
    fun `a MediaStyle notification is media`() {
        // What every well-behaved player posts, and what the template check is
        // there for.
        val n = build { style = Notification.MediaStyle() }
        assertTrue(n.isMedia())
    }

    @Test
    fun `a transport-category notification is media`() {
        // The fallback for players that hand-roll their layout instead of
        // using the platform style.
        val n = build { setCategory(Notification.CATEGORY_TRANSPORT) }
        assertTrue(n.isMedia())
    }

    @Test
    fun `an ordinary message is not media`() {
        // The filter must not quietly swallow the notifications the list
        // exists for.
        assertFalse(build { }.isMedia())
        assertFalse(build { setCategory(Notification.CATEGORY_MESSAGE) }.isMedia())
    }

    @Test
    fun `a dismissible media notification is still media`() {
        // The exact case that got through: not ongoing, so the flag check
        // passed it, and it landed under the widget saying the same thing.
        val n = build {
            style = Notification.MediaStyle()
            setOngoing(false)
        }
        assertFalse("this is the flag that failed to catch it", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("but it is unmistakably media", n.isMedia())
    }
}

package com.aura.launcher

import com.aura.launcher.notifications.NotificationHub
import com.aura.launcher.notifications.NotificationItem
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Regression coverage for listener refreshes racing a user's swipe. */
@RunWith(RobolectricTestRunner::class)
class NotificationDismissalTest {

    @Before
    fun setUp() {
        NotificationHub.publish(emptyList())
        NotificationHub.clearAll()
    }

    private fun item(key: String = "notification") = NotificationItem(
        key = key,
        packageName = "com.example.messages",
        appLabel = "Messages",
        title = "Joyce",
        text = "Até logo",
        postedAt = 1L,
        clearable = true,
        contentIntent = null,
    )

    @After
    fun cleanUp() {
        // An empty listener snapshot acknowledges every pending cancellation.
        NotificationHub.publish(emptyList())
        NotificationHub.clearAll()
    }

    @Test
    fun `stale listener refresh cannot resurrect a dismissed card`() {
        val card = item()
        NotificationHub.publish(listOf(card))

        NotificationHub.dismiss(card)
        NotificationHub.publish(listOf(card)) // system has not caught up yet

        assertEquals(emptyList<NotificationItem>(), NotificationHub.items.value)
    }

    @Test
    fun `key may appear again after listener confirms it was removed`() {
        val card = item()
        NotificationHub.publish(listOf(card))
        NotificationHub.dismiss(card)

        NotificationHub.publish(emptyList()) // cancellation acknowledged
        val later = card.copy(text = "Uma mensagem nova", postedAt = 2L)
        NotificationHub.publish(listOf(later))

        assertEquals(listOf(later), NotificationHub.items.value)
    }
}

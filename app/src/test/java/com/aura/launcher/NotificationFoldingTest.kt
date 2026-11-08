package com.aura.launcher

import com.aura.launcher.notifications.NotificationHub
import com.aura.launcher.notifications.NotificationItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Two messages from the same person are one thing on the home screen.
 *
 * The failure this exists for is not a crash. It is a shade with two cards on
 * it — "Joyce: are you coming" and "Joyce: because we start at seven" — each
 * summarised on its own, the second one meaningless without the first, and the
 * user reading twice to learn one thing.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationFoldingTest {

    private var clock = 1_000L

    private fun message(
        conversation: String,
        text: String,
        key: String = "k${clock++}",
        history: List<String> = emptyList(),
        clearable: Boolean = true,
    ) = NotificationItem(
        key = key,
        packageName = "com.whatsapp",
        appLabel = "WhatsApp",
        title = conversation,
        text = text,
        postedAt = clock,
        clearable = clearable,
        contentIntent = null,
        conversationKey = "com.whatsapp|$conversation",
        history = history,
    )

    @Test
    fun `two messages from one person become one card`() {
        val folded = NotificationHub.fold(
            listOf(
                message("Joyce", "você vem?"),
                message("Joyce", "porque a gente começa às sete"),
            )
        )
        assertEquals(1, folded.size)
        assertEquals(2, folded.first().foldedCount)
    }

    @Test
    fun `the card reads in the order the messages were written`() {
        val first = message("Joyce", "você vem?")
        val second = message("Joyce", "porque a gente começa às sete")
        val card = NotificationHub.fold(listOf(second, first)).first()
        assertEquals(
            listOf("você vem?", "porque a gente começa às sete"),
            card.thread,
        )
    }

    @Test
    fun `the newest message carries the card`() {
        // It owns the reply action and the tap target that are still current;
        // the older one's are stale the moment a newer message exists.
        val first = message("Joyce", "você vem?", key = "old")
        val second = message("Joyce", "começa às sete", key = "new")
        val card = NotificationHub.fold(listOf(first, second)).first()
        assertEquals("new", card.key)
        assertEquals(listOf("old"), card.mergedKeys)
    }

    @Test
    fun `different people stay apart`() {
        val folded = NotificationHub.fold(
            listOf(
                message("Joyce", "você vem?"),
                message("Ana", "manda o link"),
            )
        )
        assertEquals(2, folded.size)
    }

    @Test
    fun `everything that is not a conversation is left alone`() {
        // Most notifications are one of these, and folding them by title would
        // collapse a bank's two different alerts into one card.
        val plain = NotificationItem(
            key = "a",
            packageName = "com.bank",
            appLabel = "Bank",
            title = "Compra aprovada",
            text = "R$ 42,00",
            postedAt = 1,
            clearable = true,
            contentIntent = null,
        )
        val other = plain.copy(key = "b", title = "Fatura fechada", text = "vence dia 10")
        assertEquals(2, NotificationHub.fold(listOf(plain, other)).size)
    }

    @Test
    fun `a card is only clearable when everything under it is`() {
        val folded = NotificationHub.fold(
            listOf(
                message("Joyce", "primeira", clearable = false),
                message("Joyce", "segunda", clearable = true),
            )
        )
        // Otherwise the swipe clears one and leaves the other, which looks like
        // the swipe having failed.
        assertTrue(!folded.first().clearable)
    }

    // ------------------------------------------------------------ signature --

    @Test
    fun `a thread that grows is a different thing to summarise`() {
        // The whole reason the caches moved off the notification key: a
        // messaging app keeps one key while the content changes completely, so
        // the summary written for the first message stayed on screen for the
        // third.
        val one = message("Joyce", "você vem?", key = "same")
        val two = message("Joyce", "começa às sete", key = "same", history = listOf("você vem?"))
        assertNotEquals(one.signature, two.signature)
    }

    @Test
    fun `the same thread read twice is the same thing`() {
        val a = message("Joyce", "começa às sete", key = "x", history = listOf("você vem?"))
        val b = message("Joyce", "começa às sete", key = "y", history = listOf("você vem?"))
        // Different notification keys, same conversation and same words: the
        // model must not be asked again.
        assertEquals(a.signature, b.signature)
    }

    @Test
    fun `the text is never repeated in the thread`() {
        // MessagingStyle carries the newest message in both the array and
        // EXTRA_TEXT, so without dedup the model sees it twice and weighs it
        // twice.
        val card = message("Joyce", "começa às sete", history = listOf("você vem?", "começa às sete"))
        assertEquals(listOf("você vem?", "começa às sete"), card.thread)
    }
}

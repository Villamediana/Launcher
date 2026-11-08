package com.aura.launcher

import com.aura.launcher.gestures.CARRIED
import com.aura.launcher.gestures.fillCarried
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one value that travels between the steps of a gesture.
 *
 * Small enough to look like it cannot go wrong, and it is the difference
 * between "ask her to write it, then send it to him" working and a message
 * going out with the literal token in it — to a real person, from a shape the
 * user drew, with no screen in front of them to catch it.
 */
class CarriedValueTest {

    @Test
    fun `the token is replaced by what came before`() {
        assertEquals(
            "Send this: running late",
            fillCarried("Send this: $CARRIED", "running late"),
        )
    }

    @Test
    fun `text without the token is left exactly alone`() {
        // Including the identity: no trimming, no normalising, nothing. What
        // the user typed is what goes out.
        val text = "  Bom dia!  "
        assertEquals(text, fillCarried(text, "anything"))
    }

    @Test
    fun `every occurrence is replaced, not just the first`() {
        assertEquals(
            "cat and cat",
            fillCarried("$CARRIED and $CARRIED", "cat"),
        )
    }

    @Test
    fun `an empty carry empties the token rather than leaving it showing`() {
        // The first step produced nothing — she failed, or the clipboard was
        // empty. A message reading "Send this: {it}" is worse than one reading
        // "Send this:".
        assertEquals("Send this: ", fillCarried("Send this: $CARRIED", ""))
    }

    @Test
    fun `the token is not something anybody would type by accident`() {
        assertEquals("{it}", CARRIED)
        assertEquals("what about it though", fillCarried("what about it though", "X"))
    }
}

package com.aura.launcher

import android.os.Process
import com.aura.launcher.apps.AppEntry
import com.aura.launcher.ui.drawer.sectionOffsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The alphabet scrubber jumps by list index. An off-by-one here does not throw —
 * it quietly lands on the wrong app — so the arithmetic is pinned down.
 */
@RunWith(RobolectricTestRunner::class)
class DrawerIndexTest {

    private fun app(label: String) =
        AppEntry(label, "com.example.${label.lowercase()}", "Main", Process.myUserHandle())

    private val grouped = linkedMapOf(
        "A" to listOf(app("Alpha"), app("Anchor")),
        "B" to listOf(app("Bravo")),
        "C" to listOf(app("Charlie"), app("Cobalt"), app("Crest")),
    )

    @Test
    fun `each section starts after the previous header and its rows`() {
        val offsets = sectionOffsets(grouped)
        // A: header at 0, rows at 1 and 2.
        assertEquals(0, offsets["A"])
        // B: header at 3, row at 4.
        assertEquals(3, offsets["B"])
        // C: header at 5.
        assertEquals(5, offsets["C"])
    }

    @Test
    fun `offsets land on headers, never on a row`() {
        val offsets = sectionOffsets(grouped)
        // Rebuild the flat list the LazyColumn emits and check each target.
        val flat = buildList {
            for ((letter, entries) in grouped) {
                add("header-$letter")
                entries.forEach { add(it.key) }
            }
        }
        for ((letter, index) in offsets) {
            assertEquals("header-$letter", flat[index])
        }
    }

    @Test
    fun `an empty drawer produces no offsets`() {
        assertTrue(sectionOffsets(emptyMap()).isEmpty())
    }

    @Test
    fun `a single section starts at zero`() {
        val single = linkedMapOf("Z" to listOf(app("Zulu")))
        assertEquals(mapOf("Z" to 0), sectionOffsets(single))
    }

    @Test
    fun `apps bucket by first letter with everything else under hash`() {
        assertEquals("A", app("Alpha").bucket)
        assertEquals("C", app("Câmera").bucket)
        assertEquals("#", app("1Password").bucket)
        assertEquals("#", app("+Message").bucket)
    }
}

/**
 * The home screen's hidden edge opens the drawer at a letter. Both controls
 * share one alphabet and one offset table, so a mismatch between them would
 * land the user on the wrong section.
 */
@RunWith(RobolectricTestRunner::class)
class AlphabetShortcutTest {

    private fun app(label: String) =
        AppEntry(label, "com.example.${label.lowercase()}", "Main", Process.myUserHandle())

    @Test
    fun `the alphabet is fixed and starts with the non-letter bucket`() {
        assertEquals(27, com.aura.launcher.ui.drawer.ALPHABET.size)
        assertEquals("#", com.aura.launcher.ui.drawer.ALPHABET.first())
        assertEquals("Z", com.aura.launcher.ui.drawer.ALPHABET.last())
    }

    @Test
    fun `every bucket an app can produce exists in the strip`() {
        val labels = listOf("Alpha", "Câmera", "1Password", "+Message", "Zulu", "école")
        for (label in labels) {
            val bucket = app(label).bucket
            assertTrue(
                "$label bucketed to '$bucket', which the strip cannot show",
                bucket in com.aura.launcher.ui.drawer.ALPHABET,
            )
        }
    }

    @Test
    fun `jumping to a letter lands on that letter's header`() {
        val grouped = linkedMapOf(
            "#" to listOf(app("1Password")),
            "A" to listOf(app("Alpha")),
            "Z" to listOf(app("Zulu"), app("Zenith")),
        )
        val offsets = com.aura.launcher.ui.drawer.sectionOffsets(grouped)
        val flat = buildList {
            for ((letter, entries) in grouped) {
                add("header-$letter")
                entries.forEach { add(it.key) }
            }
        }
        assertEquals("header-#", flat[offsets.getValue("#")])
        assertEquals("header-A", flat[offsets.getValue("A")])
        assertEquals("header-Z", flat[offsets.getValue("Z")])
    }
}

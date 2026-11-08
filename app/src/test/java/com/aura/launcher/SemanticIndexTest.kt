package com.aura.launcher

import android.content.pm.ApplicationInfo
import android.os.Process
import com.aura.launcher.apps.AppEntry
import com.aura.launcher.apps.MatchKind
import com.aura.launcher.apps.SemanticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SemanticIndexTest {

    private fun entry(label: String, pkg: String, category: Int = -1) =
        AppEntry(label, pkg, "$pkg.MainActivity", Process.myUserHandle(), category, 1L)

    private val apps = listOf(
        entry("WhatsApp", "com.whatsapp", ApplicationInfo.CATEGORY_SOCIAL),
        entry("Telegram", "org.telegram.messenger", ApplicationInfo.CATEGORY_SOCIAL),
        entry("Google Maps", "com.google.android.apps.maps", ApplicationInfo.CATEGORY_MAPS),
        entry("Google Photos", "com.google.android.apps.photos", ApplicationInfo.CATEGORY_IMAGE),
        entry("Câmera", "com.android.camera2", ApplicationInfo.CATEGORY_IMAGE),
        entry("Spotify", "com.spotify.music", ApplicationInfo.CATEGORY_AUDIO),
        entry("Nubank", "com.nu.production"),
        entry("Settings", "com.android.settings"),
        entry("Calculator", "com.android.calculator2"),
        entry("Clock", "com.android.deskclock"),
        entry("Gmail", "com.google.android.gm"),
        entry("Instagram", "com.instagram.android", ApplicationInfo.CATEGORY_SOCIAL),
    )

    /** Cold start: nothing profiled yet, only names and system categories. */
    private val index = SemanticIndex(apps)

    @Test
    fun `exact name wins`() {
        assertEquals("Spotify", index.resolve("spotify")?.label)
    }

    @Test
    fun `accents are ignored in both directions`() {
        assertEquals("Câmera", index.resolve("camera")?.label)
        assertEquals("Câmera", index.resolve("câmera")?.label)
    }

    @Test
    fun `typos still resolve`() {
        assertEquals("WhatsApp", index.resolve("whatsap")?.label)
        assertEquals("Instagram", index.resolve("instgram")?.label)
    }

    @Test
    fun `multi word prefixes resolve`() {
        assertEquals("Google Photos", index.resolve("goog pho")?.label)
    }

    @Test
    fun `system categories carry intent before anything is profiled`() {
        // No profiles at all here: this is the first launch, before the model
        // has been downloaded. Android's own category still has to work.
        assertEquals("Spotify", index.resolve("music")?.label)
        assertEquals("Google Maps", index.resolve("navigation")?.label)
        assertNotNull(index.resolve("social"))
    }

    @Test
    fun `a real app name beats a concept match on another app`() {
        // "Gmail" is a mail app, but typing it must never surface something else.
        assertEquals("Gmail", index.resolve("gmail")?.label)
    }

    @Test
    fun `nonsense resolves to nothing`() {
        assertNull(index.resolve("qwertyuiopzxcv"))
    }

    @Test
    fun `usage counts break ties without overriding names`() {
        val biased = SemanticIndex(apps, usage = mapOf("com.telegram/x" to 500))
        // Telegram's key does not match the usage map entry, so ranking is
        // unchanged — this pins that a malformed usage key cannot corrupt search.
        assertEquals("WhatsApp", biased.resolve("whatsapp")?.label)
    }

    @Test
    fun `empty query returns the most used apps first`() {
        val usage = mapOf(apps[5].key to 30, apps[0].key to 10)
        val ranked = SemanticIndex(apps, usage).search("", limit = 2)
        assertEquals("Spotify", ranked[0].entry.label)
        assertEquals("WhatsApp", ranked[1].entry.label)
    }
}

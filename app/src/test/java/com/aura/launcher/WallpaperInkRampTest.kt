package com.aura.launcher

import androidx.compose.ui.graphics.Color
import com.aura.launcher.ui.theme.DarkAura
import com.aura.launcher.ui.theme.LightAura
import com.aura.launcher.ui.theme.wallpaperInkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ink chosen for a wallpaper has to carry all the way down the ramp.
 *
 * The first version took the muted and faint tones straight from the opposite
 * theme, which reads as correct and is not: those tones are picked to sit
 * against *that theme's paper*. The dark scheme's faint ink is a dark grey —
 * right on a dark page, invisible when it is supposed to be light ink on a
 * photograph. The visible symptom was the clock turning white while the date,
 * battery and weather line under it stayed exactly as dark as before.
 *
 * What follows pins the property that was actually wanted: every tone in the
 * ramp sits on the same side of the fence as the ink.
 */
class WallpaperInkRampTest {

    /** Perceived lightness, good enough to say "this is a light colour". */
    private fun luminance(color: Color): Float =
        0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

    @Test
    fun `light ink keeps the whole ramp light`() {
        val ramp = wallpaperInkColors(LightAura, lightInk = true)
        for ((name, tone) in listOf(
            "ink" to ramp.ink,
            "inkMuted" to ramp.inkMuted,
            "inkFaint" to ramp.inkFaint,
        )) {
            assertTrue("$name is not light: ${luminance(tone)}", luminance(tone) > 0.5f)
        }
    }

    @Test
    fun `dark ink keeps the whole ramp dark`() {
        val ramp = wallpaperInkColors(DarkAura, lightInk = false)
        for ((name, tone) in listOf(
            "ink" to ramp.ink,
            "inkMuted" to ramp.inkMuted,
            "inkFaint" to ramp.inkFaint,
        )) {
            assertTrue("$name is not dark: ${luminance(tone)}", luminance(tone) < 0.5f)
        }
    }

    @Test
    fun `the ramp still recedes`() {
        // Muted is quieter than ink, faint quieter than muted — otherwise the
        // date would shout as loudly as the clock.
        for (lightInk in listOf(true, false)) {
            val ramp = wallpaperInkColors(LightAura, lightInk)
            assertTrue(ramp.inkMuted.alpha < ramp.ink.alpha)
            assertTrue(ramp.inkFaint.alpha < ramp.inkMuted.alpha)
        }
    }

    @Test
    fun `the surfaces the theme owns are left alone`() {
        // The whole point of scoping this: choosing an ink must not repaint the
        // launcher. Only what is printed on the picture moves.
        val base = LightAura
        val ramp = wallpaperInkColors(base, lightInk = true)
        assertEquals(base.paper, ramp.paper)
        assertEquals(base.paperDeep, ramp.paperDeep)
        assertEquals(base.surface, ramp.surface)
        assertEquals(base.surfaceRaised, ramp.surfaceRaised)
    }

    @Test
    fun `what is drawn on the picture follows the ink`() {
        // The accent and the battery's charging and low tints are on the
        // photograph as much as the clock is, so they come from the scheme the
        // ink came from rather than from the theme underneath.
        val onLight = wallpaperInkColors(LightAura, lightInk = true)
        assertEquals(DarkAura.accent, onLight.accent)
        assertEquals(DarkAura.sage, onLight.sage)
        assertEquals(DarkAura.danger, onLight.danger)
        assertTrue("glyphs keying off isDark must follow the ink", onLight.isDark)

        val onDark = wallpaperInkColors(DarkAura, lightInk = false)
        assertEquals(LightAura.accent, onDark.accent)
        assertTrue("glyphs keying off isDark must follow the ink", !onDark.isDark)
    }
}

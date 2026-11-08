package com.aura.launcher.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Aura's palette is deliberately not Material's. The launcher is meant to read
 * like warm paper with ink on it, so the ramp is built from a single warm grey
 * axis plus two low-saturation accents (terracotta and sage). Everything else
 * is derived by alpha, never by a second hue.
 */
@Immutable
data class AuraColors(
    val paper: Color,
    val paperDeep: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val hairline: Color,
    val accent: Color,
    val accentSoft: Color,
    val sage: Color,
    val danger: Color,
    val isDark: Boolean,
)

val LightAura = AuraColors(
    paper = Color(0xFFF7F3EC),
    paperDeep = Color(0xFFEFE9DE),
    surface = Color(0xFFFDFBF6),
    surfaceRaised = Color(0xFFFFFFFF),
    // Softened away from near-black. Pure ink on paper reads as a printed
    // heading; this is the tone of a body paragraph in a book — dark enough
    // to be legible on the warm background, light enough that a screen full
    // of it does not feel typed in permanent marker.
    ink = Color(0xFF3A342B),
    inkMuted = Color(0xFF7B7364),
    inkFaint = Color(0xFFA69C8C),
    hairline = Color(0x14201C16),
    accent = Color(0xFFB4633A),
    accentSoft = Color(0x1AB4633A),
    sage = Color(0xFF6C7A5B),
    danger = Color(0xFFA83A2E),
    isDark = false,
)

val DarkAura = AuraColors(
    paper = Color(0xFF14120F),
    paperDeep = Color(0xFF0E0D0B),
    surface = Color(0xFF1C1915),
    surfaceRaised = Color(0xFF24201B),
    ink = Color(0xFFF0EADF),
    inkMuted = Color(0xFF9C9384),
    inkFaint = Color(0xFF6B6458),
    hairline = Color(0x1AF0EADF),
    accent = Color(0xFFDE8B5C),
    accentSoft = Color(0x1FDE8B5C),
    sage = Color(0xFF97A585),
    danger = Color(0xFFE0705F),
    isDark = true,
)

val LocalAuraColors = staticCompositionLocalOf { LightAura }

object Aura {
    val colors: AuraColors
        @Composable @ReadOnlyComposable get() = LocalAuraColors.current
}

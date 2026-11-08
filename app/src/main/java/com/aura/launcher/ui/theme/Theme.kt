package com.aura.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Whether this resolves to dark, for callers with no composition to ask.
 *
 * The theme is normally read through `isSystemInDarkTheme()`, which needs to be
 * inside a composable. Anything building HTML for the conversation card is not
 * — it runs on a tool's dispatcher — so SYSTEM has to be resolved from the
 * configuration directly.
 */
fun ThemeMode.isDark(context: android.content.Context): Boolean = when (this) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
}

/**
 * Aura's two voices.
 *
 * Fraunces only in the Editorial clock, which is opt-in. Every title in the
 * app — settings, sheets, widgets, the drawer — is the system sans; the serif
 * was tried on those in 1.128–1.129 and read as a different app.
 *
 * The face: a soft old-style serif, at its "soft" setting so the terminals
 * round off like ink that spread a little. A variable font bundled in
 * res/font (OFL, licence in assets/licenses).
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object AuraFonts {
    private fun serif(weight: Int, soft: Float = 100f) = androidx.compose.ui.text.font.Font(
        com.aura.launcher.R.font.fraunces,
        weight = FontWeight(weight),
        variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
            androidx.compose.ui.text.font.FontVariation.weight(weight),
            androidx.compose.ui.text.font.FontVariation.Setting("SOFT", soft),
            androidx.compose.ui.text.font.FontVariation.Setting("opsz", 72f),
        ),
    )

    val Serif: FontFamily = FontFamily(serif(300), serif(400), serif(500), serif(600), serif(700))
    /**
     * The phone's own sans for everything read as interface. Tried with Inter
     * bundled (1.128.0): its taller line metrics loosened every list on the
     * home screen and the text stopped looking like the phone's — reverted.
     */
    val Sans: FontFamily = FontFamily.SansSerif
}

private val AuraTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 76.sp,
        lineHeight = 80.sp,
        letterSpacing = (-2.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AuraFonts.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = AuraFonts.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.5.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AuraFonts.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = AuraFonts.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = AuraFonts.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.4.sp,
    ),
)

/** Corner radii used across the app; kept in one place so surfaces agree. */
object AuraShape {
    val pill = 999.dp
    val card = 24.dp
    val chip = 14.dp
    val sheet = 32.dp
}

@Composable
fun AuraTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkAura else LightAura

    // Material components (text fields, sliders, switches) still read the M3
    // scheme, so it is mapped onto the Aura palette rather than left default.
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.paperDeep,
            secondary = colors.sage,
            background = colors.paper,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.inkMuted,
            outline = colors.inkFaint,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.surfaceRaised,
            secondary = colors.sage,
            background = colors.paper,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.paperDeep,
            onSurfaceVariant = colors.inkMuted,
            outline = colors.inkFaint,
            error = colors.danger,
        )
    }

    CompositionLocalProvider(LocalAuraColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = AuraTypography) {
            CompositionLocalProvider(
                LocalTextStyle provides AuraTypography.bodyLarge.copy(color = colors.ink),
                content = content,
            )
        }
    }
}

/**
 * Prints [content] in the ink colour the user picked for their wallpaper.
 *
 * Only the ink ramp moves — paper, surfaces and the accent stay exactly as the
 * theme set them. That is the whole point of the setting: on a photograph the
 * clock may need to be white while the launcher itself is still on the light
 * theme, and picking "light ink" must not flip the drawer, the settings panel
 * or the cards into the dark scheme. It did, and that was the bug.
 *
 * Wrap only what is drawn straight onto the picture — the clock, the strip
 * under it, the favourites, the chevron. Anything sitting on its own surface
 * keeps the theme's ink, because that is what its background was picked for.
 *
 * [lightInk] null means "follow the theme" and costs nothing: [content] is
 * composed with the palette already in scope.
 */
@Composable
fun OnWallpaperInk(lightInk: Boolean?, content: @Composable () -> Unit) {
    if (lightInk == null) {
        content()
        return
    }
    val base = Aura.colors
    val shifted = remember(base, lightInk) { wallpaperInkColors(base, lightInk) }
    CompositionLocalProvider(
        LocalAuraColors provides shifted,
        LocalTextStyle provides LocalTextStyle.current.copy(color = shifted.ink),
        content = content,
    )
}

/**
 * [base] with everything that gets printed on the wallpaper switched to the ink
 * the user picked, and nothing else touched.
 *
 * Split out of [OnWallpaperInk] because it is the part with a right and a wrong
 * answer, and the wrong one is not visible in a type check — it is visible on a
 * phone, at night, as a date you cannot read.
 */
fun wallpaperInkColors(base: AuraColors, lightInk: Boolean): AuraColors {
    val source = if (lightInk) DarkAura else LightAura
    return base.copy(
        ink = source.ink,
        // Derived from the ink itself by alpha, the way the palette says the
        // ramp is built — not lifted from the other scheme's muted and faint
        // tones. Those are chosen to sit against that scheme's paper: the dark
        // one's "faint" is a dark grey, right on a dark page and invisible as
        // light ink on a photograph. Which is exactly what went wrong — light
        // ink lit the clock and left the date, the battery and the weather as
        // dark as they already were.
        //
        // And the alphas are nearly solid, which they are not on paper. A
        // photograph is not a flat known background: 56% white over a busy
        // picture is a grey smear, and beside a clock printed at full strength
        // it does not read as "quieter", it reads as "the ink setting missed
        // this line" — which is precisely the complaint about the date, the
        // battery and the temperature. Over the wallpaper the ramp separates
        // by a hair, and every step of it is the ink the user picked.
        inkMuted = source.ink.copy(alpha = 0.95f),
        inkFaint = source.ink.copy(alpha = 0.85f),
        hairline = source.ink.copy(alpha = 0.22f),
        // On the picture as much as the clock is: the accent on the ring and
        // poster faces, and the tints the battery takes while it charges or
        // runs low. From the matching scheme, so they carry against the same
        // photograph the ink was chosen for.
        accent = source.accent,
        accentSoft = source.accentSoft,
        sage = source.sage,
        danger = source.danger,
        // Anything asking "am I drawing on something dark?" is, in here, asking
        // which way the ink runs. The weather glyphs pick their cloud and rain
        // tones off exactly this.
        isDark = lightInk,
    )
}

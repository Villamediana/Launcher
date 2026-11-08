package com.aura.launcher.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.launcher.AuraApp
import com.aura.launcher.R
import com.aura.launcher.data.Settings
import com.aura.launcher.data.WallpaperInk
import com.aura.launcher.ui.theme.Aura
import com.aura.launcher.ui.theme.AuraColors
import com.aura.launcher.ui.theme.DarkAura
import com.aura.launcher.ui.theme.LightAura
import com.aura.launcher.ui.theme.ThemeMode
import com.aura.launcher.ui.theme.WallpaperStore
import com.aura.launcher.ui.theme.rememberWallpaperBitmap
import com.aura.launcher.ui.theme.wallpaperBackground
import com.aura.launcher.ui.theme.wallpaperInkColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How the launcher looks — and, since everything else on the home screen stopped
 * being a switch, the whole of the first settings page.
 *
 * Three decisions live here and all three are visual, so all three are shown
 * rather than described: the theme as three little home screens, the wallpaper
 * as the wallpaper, and the ink over it printed on top of the actual picture in
 * the actual colour. A row of words with a radio button beside each one asks the
 * reader to imagine the result; this page just draws it.
 */
@Composable
internal fun AppearanceSettings(
    app: AuraApp,
    settings: Settings,
    update: ((Settings) -> Settings) -> Unit,
) {
    ThemePicker(
        selected = settings.themeMode,
        onSelect = { mode -> update { it.copy(themeMode = mode) } },
    )
    Spacer(Modifier.height(16.dp))
    WallpaperCard(app = app, settings = settings, update = update)
}

// ----------------------------------------------------------------- theme ----

/**
 * Light, dark and system, drawn as the thing they produce.
 *
 * The three tiles are the same miniature home screen painted in the palette each
 * mode resolves to — the sun rides over the light one, a crescent and four stars
 * over the dark one, and System is cut down the middle so you can see it is both.
 * These are deliberately still previews. Appearance already has several canvas
 * tiles, and continuously animating each option adds work without changing the
 * choice the preview communicates.
 */
@Composable
private fun ThemePicker(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val colors = Aura.colors

    SettingsGroup {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
            Text(
                text = stringResource(R.string.settings_theme_title),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.settings_theme_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkFaint,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (mode in listOf(ThemeMode.LIGHT, ThemeMode.SYSTEM, ThemeMode.DARK)) {
                    ThemeTile(
                        mode = mode,
                        active = mode == selected,
                        onClick = { onSelect(mode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeTile(
    mode: ThemeMode,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Aura.colors
    // One number drives the whole selected state — the lift, the ring, the
    // weight of the label — so the tile arrives as a single movement instead of
    // three properties changing at once at three different speeds.
    val on by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "themeTileSelected",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // A phone, roughly. Tall enough that the miniature reads as a
                // screen and not as a swatch.
                .aspectRatio(0.62f)
                .graphicsLayer {
                    val s = 0.965f + 0.035f * on
                    scaleX = s
                    scaleY = s
                }
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                when (mode) {
                    ThemeMode.LIGHT -> drawMiniHome(LightAura, bodyX = 0.5f, spin = 32f, pulse = 0.6f)
                    ThemeMode.DARK -> drawMiniHome(DarkAura, bodyX = 0.5f, spin = 32f, pulse = 0.6f)
                    ThemeMode.SYSTEM -> drawSplitHome(spin = 32f, pulse = 0.6f)
                }
            }
            // The ring is painted over the miniature rather than around it, so
            // selecting a tile does not move the two beside it.
            Box(
                Modifier
                    .fillMaxSize()
                    .border(
                        width = (1f + 1.4f * on).dp,
                        color = lerp(colors.hairline, colors.accent, on),
                        shape = RoundedCornerShape(18.dp),
                    )
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (on > 0.5f) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = stringResource(
                    when (mode) {
                        ThemeMode.LIGHT -> R.string.settings_theme_light
                        ThemeMode.DARK -> R.string.settings_theme_dark
                        ThemeMode.SYSTEM -> R.string.settings_theme_system
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = lerp(colors.inkMuted, colors.accent, on),
            )
        }
    }
}

/**
 * System, as one screen lit from two sides.
 *
 * A diagonal rather than a straight split: a vertical seam down the middle of a
 * rounded rectangle reads as two tiles that happen to touch, and the whole point
 * of this one is that it is a single screen which changes its mind at sunset.
 */
private fun DrawScope.drawSplitHome(spin: Float, pulse: Float) {
    val w = size.width
    val h = size.height
    val topX = w * 0.60f
    val bottomX = w * 0.40f

    clipPath(
        Path().apply {
            moveTo(0f, 0f); lineTo(topX, 0f); lineTo(bottomX, h); lineTo(0f, h); close()
        }
    ) { drawMiniHome(LightAura, bodyX = 0.27f, spin = spin, pulse = pulse) }

    clipPath(
        Path().apply {
            moveTo(topX, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(bottomX, h); close()
        }
    ) { drawMiniHome(DarkAura, bodyX = 0.73f, spin = spin, pulse = pulse) }

    // A hairline on the seam, so the two halves meet on a drawn edge instead of
    // on the anti-aliasing of two fills that nearly line up.
    drawLine(
        color = Color.White.copy(alpha = 0.10f),
        start = Offset(topX, 0f),
        end = Offset(bottomX, h),
        strokeWidth = 1.dp.toPx(),
    )
}

/**
 * The miniature: paper, something in the sky, a clock, a date and two cards.
 *
 * Every measurement is a fraction of the tile, because the same drawing has to
 * hold at a third of a phone's width in the picker and at whatever width three
 * of them end up with on a small screen.
 */
private fun DrawScope.drawMiniHome(c: AuraColors, bodyX: Float, spin: Float, pulse: Float) {
    val w = size.width
    val h = size.height

    drawRect(color = c.paper)

    val sky = Offset(w * bodyX, h * 0.20f)
    val r = w * 0.105f

    if (c.isDark) {
        // A crescent cut by a second circle in the paper colour — the cheapest
        // moon there is, and it only works because what is behind it is a flat
        // fill. Which, here, it always is.
        drawCircle(color = c.ink.copy(alpha = 0.88f), radius = r, center = sky)
        drawCircle(
            color = c.paper,
            radius = r * 0.94f,
            center = sky + Offset(r * 0.60f, -r * 0.34f),
        )
        val stars = listOf(
            0.20f to 0.09f,
            0.80f to 0.15f,
            0.63f to 0.05f,
            0.33f to 0.28f,
        )
        stars.forEachIndexed { i, (sx, sy) ->
            // Each star runs the same cycle a quarter turn apart, so the sky
            // breathes instead of blinking on and off in unison.
            val phase = (pulse + i * 0.23f) * 2f * PI.toFloat()
            val alpha = 0.22f + 0.55f * abs(sin(phase))
            drawCircle(
                color = c.ink.copy(alpha = alpha),
                radius = w * 0.012f,
                center = Offset(w * sx, h * sy),
            )
        }
    } else {
        repeat(8) { i ->
            val a = ((spin + i * 45f) * PI / 180f).toFloat()
            val dx = sin(a)
            val dy = -cos(a)
            drawLine(
                color = c.accent.copy(alpha = 0.34f),
                start = sky + Offset(dx * r * 1.55f, dy * r * 1.55f),
                end = sky + Offset(dx * r * 2.10f, dy * r * 2.10f),
                strokeWidth = w * 0.016f,
                cap = StrokeCap.Round,
            )
        }
        drawCircle(color = c.accent.copy(alpha = 0.16f), radius = r * 1.42f, center = sky)
        drawCircle(color = c.accent, radius = r, center = sky)
    }

    // The clock, the date under it, and the two cards that sit below both. Bars
    // rather than glyphs: at this size real type is a smudge, and a smudge looks
    // like a rendering fault where a bar reads as "there is a clock here".
    drawRoundRect(
        color = c.ink.copy(alpha = 0.88f),
        topLeft = Offset(w * 0.21f, h * 0.44f),
        size = Size(w * 0.58f, h * 0.070f),
        cornerRadius = CornerRadius(h * 0.018f),
    )
    drawRoundRect(
        color = c.inkMuted.copy(alpha = 0.75f),
        topLeft = Offset(w * 0.32f, h * 0.545f),
        size = Size(w * 0.36f, h * 0.028f),
        cornerRadius = CornerRadius(h * 0.014f),
    )
    for (y in listOf(0.645f, 0.795f)) {
        val topLeft = Offset(w * 0.12f, h * y)
        val cardSize = Size(w * 0.76f, h * 0.105f)
        val corner = CornerRadius(w * 0.055f)
        drawRoundRect(color = c.surface, topLeft = topLeft, size = cardSize, cornerRadius = corner)
        drawRoundRect(
            color = c.hairline,
            topLeft = topLeft,
            size = cardSize,
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

// ------------------------------------------------------------- wallpaper ----

/**
 * The backdrop, shown as itself — one choice, not two.
 *
 * There used to be two cards here: a photograph, and, underneath, a picker of
 * drawn backdrops that the photograph silently overrode. Two controls for one
 * surface, one of which spent most of its life greyed out explaining that the
 * other had won. They are the same question — what is behind the clock — so
 * they are now the same row of tiles: paper, night sky, constellation, or a
 * picture of your own. Picking any of them is picking all of them, because
 * only one can be true.
 *
 * The preview above the tiles is the answer, at size, with a clock and today's
 * date printed over it exactly as the home screen prints them — same colours,
 * same ramp, through the same [wallpaperInkColors] the launcher uses. So the
 * ink choice below is not a word to be imagined against a photograph you are
 * not looking at: tap it and the clock in front of you changes colour.
 */
@Composable
private fun WallpaperCard(
    app: AuraApp,
    settings: Settings,
    update: ((Settings) -> Settings) -> Unit,
) {
    val colors = Aura.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The URI the picker just returned. The editor opens on top and takes
    // pan / zoom / rotate before we commit to filesDir, so the user sees
    // exactly what will land on the home screen.
    var editing by remember { mutableStateOf<android.net.Uri?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> editing = uri }

    editing?.let { uri ->
        com.aura.launcher.ui.home.WallpaperEditor(
            source = uri,
            onCancel = { editing = null },
            onApply = { bitmap ->
                scope.launch {
                    val path = WallpaperStore.saveBitmap(context, bitmap)
                    if (path != null) update { it.copy(wallpaperPath = path) }
                    // The same picture goes on the lock screen, always. Picking
                    // a wallpaper and then finding the old one still there on
                    // unlock is the launcher only half-answering the request;
                    // the crop is already the one made for this screen, so it
                    // is the right bitmap to hand over.
                    WallpaperStore.applyToLockScreen(context, bitmap)
                    editing = null
                }
            },
        )
    }

    val hasWallpaper = settings.wallpaperPath.isNotEmpty()
    val image = rememberWallpaperBitmap(settings.wallpaperPath)

    // The slider's position while a finger is on it. Settings stay the truth,
    // but they come back through DataStore a beat later, and a control that
    // waits for that round trip drags behind the thumb. So the veil follows the
    // finger and the value is written when it lifts — no save button either
    // way, which is the point.
    var sliding by remember { mutableStateOf<Float?>(null) }
    val dim = sliding ?: settings.wallpaperDim
    val inkColors = when {
        !hasWallpaper -> colors
        settings.wallpaperInk == WallpaperInk.LIGHT -> wallpaperInkColors(colors, lightInk = true)
        settings.wallpaperInk == WallpaperInk.DARK -> wallpaperInkColors(colors, lightInk = false)
        else -> colors
    }

    // Formatted once. A settings panel that ticks over at midnight is a
    // solution to a problem nobody has.
    val now = remember { Date() }
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val day = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now) }

    SettingsGroup {
        Column {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text(
                    text = stringResource(R.string.settings_wallpaper_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.ink,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_wallpaper_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkFaint,
                )

                Spacer(Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.62f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.paper)
                        .then(
                            if (image != null) Modifier.wallpaperBackground(image, dim)
                            else Modifier
                        )
                        .border(1.dp, colors.hairline, RoundedCornerShape(18.dp)),
                ) {
                    // The full procedural renderer remains on the home screen.
                    // Settings uses a still, lightweight representation so
                    // entering Appearance cannot start several GPU-heavy scenes
                    // at once on top of the already-running home wallpaper.
                    if (!hasWallpaper) {
                        BackdropPreview(
                            kind = settings.animatedWallpaper,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 22.dp),
                    ) {
                        Text(
                            text = time,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Light,
                            color = inkColors.ink,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = day,
                            fontSize = 12.sp,
                            color = inkColors.inkMuted,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // The four backdrops, side by side. Choosing a drawn one throws
                // the photograph away rather than shadowing it: two backdrops
                // cannot both be behind the clock, and a setting that is kept
                // but ignored is the thing this card was merged to get rid of.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (kind in com.aura.launcher.data.AnimatedWallpaper.entries) {
                        BackdropTile(
                            active = !hasWallpaper && kind == settings.animatedWallpaper,
                            label = stringResource(labelFor(kind)),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (hasWallpaper) WallpaperStore.clear(context)
                                update {
                                    it.copy(
                                        wallpaperPath = "",
                                        wallpaperInk = WallpaperInk.FOLLOW,
                                        // The veil belonged to the photograph;
                                        // a drawn backdrop was designed at the
                                        // brightness it is drawn at.
                                        wallpaperDim = 0f,
                                        animatedWallpaper = kind,
                                    )
                                }
                            },
                        ) {
                            BackdropPreview(kind = kind)
                        }
                    }
                    // The fourth tile is the gallery. Tapping it opens the
                    // picker whether or not a picture is already set, so
                    // "change it" and "choose one" are the same tap.
                    BackdropTile(
                        active = hasWallpaper,
                        label = stringResource(R.string.settings_wallpaper_gallery),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(colors.paperDeep)
                                .then(
                                    if (image != null) Modifier.wallpaperBackground(image, dim)
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (image == null) {
                                Icon(
                                    imageVector = Icons.Rounded.Image,
                                    contentDescription = null,
                                    tint = colors.inkFaint,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Only while there is a picture to print on. With no wallpaper the
            // ink follows the theme and there is nothing to choose.
            if (hasWallpaper) {
                Divider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_wallpaper_overlay_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.ink,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.settings_wallpaper_overlay_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkFaint,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        // The number, so a veil that was set once can be found
                        // again — a slider at rest says nothing about where it
                        // is if the picture behind it is already dark.
                        Text(
                            text = "${(dim * 100f).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (dim > 0f) colors.accent else colors.inkFaint,
                        )
                    }
                    Slider(
                        value = dim,
                        onValueChange = { sliding = it },
                        onValueChangeFinished = {
                            val settled = sliding ?: return@Slider
                            update { it.copy(wallpaperDim = settled) }
                            sliding = null
                        },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accent,
                            activeTrackColor = colors.accent,
                            inactiveTrackColor = colors.ink.copy(alpha = 0.12f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Divider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_wallpaper_ink_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.ink,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_wallpaper_ink_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkFaint,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (ink in WallpaperInk.entries) {
                            InkChip(
                                ink = ink,
                                active = ink == settings.wallpaperInk,
                                onClick = { update { it.copy(wallpaperInk = ink) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One backdrop to choose from, painted as itself.
 *
 * The drawn ones use a lightweight still preview of the same palette and
 * composition. The full procedural scene remains on the home surface; running
 * several copies here was unnecessary work in a settings page.
 */
@Composable
private fun BackdropTile(
    active: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = Aura.colors
    val on by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "backdropTile",
    )

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.86f)
                .graphicsLayer {
                    val s = 0.965f + 0.035f * on
                    scaleX = s
                    scaleY = s
                }
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick),
        ) {
            content()
            // Painted over the tile rather than around the scene inside it, so
            // the ring does not repaint at 60fps with the wallpaper.
            Box(
                Modifier
                    .fillMaxSize()
                    .border(
                        width = (1f + 1.4f * on).dp,
                        color = lerp(colors.hairline, colors.accent, on),
                        shape = RoundedCornerShape(14.dp),
                    )
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = lerp(colors.inkMuted, colors.accent, on),
            textAlign = TextAlign.Center,
            // Two, because a tile is a quarter of the card wide and
            // "Constelação" does not fit on one line of it.
            maxLines = 2,
        )
    }
}

/** Stable, cheap previews for a page that draws several options at once. */
@Composable
private fun BackdropPreview(
    kind: com.aura.launcher.data.AnimatedWallpaper,
    modifier: Modifier = Modifier,
) {
    val paper = Aura.colors.paper
    Canvas(modifier.fillMaxSize()) {
        when (kind) {
            com.aura.launcher.data.AnimatedWallpaper.NONE -> drawRect(paper)
            com.aura.launcher.data.AnimatedWallpaper.NIGHT_SKY -> {
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xFF071020), Color(0xFF27375C)),
                    ),
                )
                val moon = Offset(size.width * 0.70f, size.height * 0.27f)
                drawCircle(Color(0xFFFFF3DC), size.minDimension * 0.12f, moon)
                drawCircle(
                    Color(0xFF101B35),
                    size.minDimension * 0.115f,
                    moon + Offset(size.minDimension * 0.065f, -size.minDimension * 0.035f),
                )
                drawRect(
                    color = Color(0xFF0B1428),
                    topLeft = Offset(0f, size.height * 0.70f),
                    size = Size(size.width, size.height * 0.30f),
                )
            }
            com.aura.launcher.data.AnimatedWallpaper.CONSTELLATION -> {
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xFF090D25), Color(0xFF242B5B)),
                    ),
                )
                val points = listOf(
                    Offset(size.width * 0.18f, size.height * 0.67f),
                    Offset(size.width * 0.35f, size.height * 0.38f),
                    Offset(size.width * 0.55f, size.height * 0.53f),
                    Offset(size.width * 0.76f, size.height * 0.25f),
                    Offset(size.width * 0.86f, size.height * 0.57f),
                )
                points.zipWithNext().forEach { (from, to) ->
                    drawLine(Color(0xFF9DB4F0), from, to, size.minDimension * 0.012f)
                }
                points.forEach { point ->
                    drawCircle(Color(0xFFF4F7FF), size.minDimension * 0.025f, point)
                }
            }
        }
    }
}

/**
 * One ink choice, with the ink on it.
 *
 * The swatch is the answer: a white dot, a dark dot, or a dot split between the
 * two for "follow the theme". Three words with a border around them were the old
 * version of this and they all looked the same.
 */
@Composable
private fun InkChip(
    ink: WallpaperInk,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Aura.colors
    val on by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "inkChip",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(lerp(colors.paperDeep, colors.accent.copy(alpha = 0.13f), on))
            .border(
                width = (1f + 0.6f * on).dp,
                color = lerp(colors.hairline, colors.accent.copy(alpha = 0.45f), on),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val r = size.minDimension / 2f
            val centre = Offset(r, r)
            when (ink) {
                WallpaperInk.LIGHT -> drawCircle(DarkAura.ink, r, centre)
                WallpaperInk.DARK -> drawCircle(LightAura.ink, r, centre)
                WallpaperInk.FOLLOW -> {
                    // Half and half, split on the same diagonal the System theme
                    // tile uses, so "follow" means one thing across the page.
                    drawCircle(LightAura.ink, r, centre)
                    clipPath(
                        Path().apply {
                            moveTo(size.width * 0.6f, 0f)
                            lineTo(size.width, 0f)
                            lineTo(size.width, size.height)
                            lineTo(size.width * 0.4f, size.height)
                            close()
                        }
                    ) { drawCircle(DarkAura.ink, r, centre) }
                }
            }
            drawCircle(
                color = Color.Black.copy(alpha = 0.14f),
                radius = r,
                center = centre,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                when (ink) {
                    WallpaperInk.FOLLOW -> R.string.settings_wallpaper_ink_follow
                    WallpaperInk.LIGHT -> R.string.settings_wallpaper_ink_light
                    WallpaperInk.DARK -> R.string.settings_wallpaper_ink_dark
                }
            ),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = lerp(colors.inkMuted, colors.accent, on),
        )
    }
}


private fun labelFor(kind: com.aura.launcher.data.AnimatedWallpaper): Int = when (kind) {
    com.aura.launcher.data.AnimatedWallpaper.NONE -> R.string.settings_live_wallpaper_none
    com.aura.launcher.data.AnimatedWallpaper.NIGHT_SKY -> R.string.settings_live_wallpaper_night
    com.aura.launcher.data.AnimatedWallpaper.CONSTELLATION -> R.string.settings_live_wallpaper_constellation
}

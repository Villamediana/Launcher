package com.aura.launcher.ui.home

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aura.launcher.AuraApp
import com.aura.launcher.data.Weather
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** What the sky is doing, as the home draws it. */
enum class SkyKind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, HEAVY_RAIN, STORM, SNOW, HEAVY_SNOW }

data class Sky(val kind: SkyKind, val day: Boolean, val celsius: Int, val windKmh: Float = 0f, val hour: Int = -1)

/** WMO weather codes (and the hour's rain) into what is drawn. */
fun skyOf(w: Weather, hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): Sky {
    val kind = when (w.code) {
        0 -> SkyKind.CLEAR
        1, 2 -> SkyKind.PARTLY
        3 -> SkyKind.CLOUDY
        45, 48 -> SkyKind.FOG
        51, 53, 56 -> SkyKind.DRIZZLE
        55, 57, 61, 80 -> if (w.precipitationMm >= 2.5f) SkyKind.RAIN else SkyKind.DRIZZLE
        63, 66, 81 -> if (w.precipitationMm >= 7.5f) SkyKind.HEAVY_RAIN else SkyKind.RAIN
        65, 67, 82 -> SkyKind.HEAVY_RAIN
        71, 73, 77, 85 -> SkyKind.SNOW
        75, 86 -> SkyKind.HEAVY_SNOW
        in 95..99 -> SkyKind.STORM
        else -> SkyKind.CLEAR
    }
    return Sky(kind, w.isDay ?: (hour in 6..18), w.celsius, w.windKmh, hour)
}

/** Cheap, repeatable "random" numbers: the same particle is the same every frame. */
internal fun hash(i: Int, salt: Int = 0): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177
    return ((x xor (x ushr 16)) and 0xFFFFFF) / 16777215f
}

/**
 * The weather, live, over the wallpaper and under everything else.
 *
 * Particles are a function of time, not objects: a raindrop's position is
 * worked out from its index and the clock, so a frame allocates nothing. The
 * frame rate follows what is drawn — rain and snow at 30, clouds and fog at
 * 15, a clear sky at 10 — and drops to a still frame on battery saver or a
 * low battery. Nothing runs while the home is not on screen.
 */
@Composable
fun WeatherSky(app: AuraApp, enabled: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val live by produceState<Sky?>(null, enabled, owner) {
        if (!enabled) { value = null; return@produceState }
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = runCatching { app.weather.current() }.getOrNull()?.let { skyOf(it) } ?: value
                delay(10 * 60_000L)
            }
        }
    }
    val sky = live ?: return
    val t = rememberSkyClock(sky, active)
    Canvas(modifier.fillMaxSize()) { drawSky(sky, t.value) }
}

/**
 * The sky's clock, ticking while [active] and the screen is in front of
 * someone: on the home screen's shared clock ([com.aura.launcher.ui.theme.Ambient]),
 * slower on battery saver or a low battery — never stopped, a still sky reads
 * as a broken one.
 */
@Composable
internal fun rememberSkyClock(sky: Sky, active: Boolean): androidx.compose.runtime.State<Float> {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val t = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(sky.kind, active) {
        if (!active) return@LaunchedEffect
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // The home screen's shared clock: on the orb's ticks, one frame for both.
            val fps = com.aura.launcher.ui.theme.Ambient.fps(context)
            val base = t.value
            val start = withFrameNanos { it }
            while (true) {
                com.aura.launcher.ui.theme.Ambient.nextTick(fps)
                withFrameNanos { now -> t.value = base + (now - start) / 1e9f }
            }
        }
    }
    return t
}

/**
 * A sky in a frame, animated, for choosing one in Ajustes: [background] is
 * the wallpaper, so it is seen over what it will be over.
 */
@Composable
fun SkyPreview(sky: Sky, background: androidx.compose.ui.graphics.ImageBitmap?, modifier: Modifier = Modifier) {
    val t = rememberSkyClock(sky, true)
    Canvas(modifier) {
        if (background != null) {
            // Cropped to fill, as the home shows it.
            val scale = maxOf(size.width / background.width, size.height / background.height)
            val w = background.width * scale
            val h = background.height * scale
            drawImage(
                background,
                dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
            )
        } else {
            drawRect(Brush.linearGradient(listOf(Color(0xFF6B5A4C), Color(0xFF2E2A26))))
        }
        drawSky(sky, t.value)
    }
}

/** Battery saver on, or under 15% and not charging: a still sky. */

internal fun DrawScope.drawSky(sky: Sky, t: Float) {
    // The light first: night deepens, rain greys, a storm darkens from above.
    when {
        sky.kind == SkyKind.STORM -> drawRect(Brush.verticalGradient(listOf(Color(0xFF151B26).copy(alpha = 0.55f), Color(0xFF1B2230).copy(alpha = 0.28f))))
        sky.kind == SkyKind.HEAVY_RAIN -> drawRect(Brush.verticalGradient(listOf(Color(0xFF232C38).copy(alpha = 0.42f), Color(0xFF2A3340).copy(alpha = 0.18f))))
        sky.kind == SkyKind.RAIN || sky.kind == SkyKind.DRIZZLE -> drawRect(Brush.verticalGradient(listOf(Color(0xFF465260).copy(alpha = 0.30f), Color(0xFF4A5563).copy(alpha = 0.10f))))
        sky.kind == SkyKind.FOG -> drawRect(Color(0xFF8C939B).copy(alpha = 0.10f))
        !sky.day -> drawRect(Brush.verticalGradient(listOf(Color(0xFF0A1230).copy(alpha = 0.34f), Color(0xFF0E1630).copy(alpha = 0.14f))))
    }
    // Dawn and dusk: the sky warms from the horizon up.
    if (sky.hour in 5..7 || sky.hour in 17..19) {
        val dusk = sky.hour >= 17
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, (if (dusk) Color(0xFFFF7A59) else Color(0xFFFFB37A)).copy(alpha = 0.14f), (if (dusk) Color(0xFFB24A7A) else Color(0xFFFFD9A0)).copy(alpha = 0.10f)),
                startY = 0f,
                endY = size.height * 0.7f,
            ),
        )
    }

    when (sky.kind) {
        SkyKind.CLEAR -> { if (sky.day) sun(t, sky.celsius) else night(t); clouds(t, 2, 0.35f, sky.day) }
        SkyKind.PARTLY -> { if (sky.day) sun(t, sky.celsius, dim = 0.6f) else night(t, stars = 40); clouds(t, 3, 0.8f, sky.day) }
        SkyKind.CLOUDY -> clouds(t, 6, 1f, sky.day)
        SkyKind.FOG -> fog(t)
        SkyKind.DRIZZLE -> { clouds(t, 5, 0.8f, sky.day, grey = 0.3f); rain(t, 90, 0.4f, sky.windKmh, light = true); glass(t, 14, 0.6f) }
        SkyKind.RAIN -> { clouds(t, 6, 0.9f, sky.day, grey = 0.5f); rain(t, 190, 0.75f, sky.windKmh); glass(t, 22, 0.9f) }
        SkyKind.HEAVY_RAIN -> { clouds(t, 7, 1f, sky.day, grey = 0.7f); rain(t, 340, 1f, sky.windKmh + 12f); splashes(t, 30); glass(t, 30, 1f) }
        SkyKind.STORM -> { val flash = flashOf(t); clouds(t, 7, 1f, false, grey = 0.85f, lit = flash); lightning(t, flash); rain(t, 360, 1f, sky.windKmh + 18f); splashes(t, 34); glass(t, 30, 1f) }
        SkyKind.SNOW -> { clouds(t, 5, 0.8f, sky.day, grey = 0.2f); snow(t, 80, sky.windKmh); settled(0.10f) }
        SkyKind.HEAVY_SNOW -> { clouds(t, 6, 0.9f, sky.day, grey = 0.35f); snow(t, 170, sky.windKmh + 8f); settled(0.16f) }
    }

    // How it feels: a frost at the edges when it is cold, a haze when it is hot.
    if (sky.celsius <= 8) frost((8 - sky.celsius).coerceIn(2, 12) / 12f)
    if (sky.celsius >= 30 && sky.day) heat(t, (sky.celsius - 29).coerceIn(1, 8) / 8f)
}

// ------------------------------------------------------------------ sun --

private fun DrawScope.sun(t: Float, celsius: Int, dim: Float = 1f) {
    val c = Offset(size.width * 0.86f, size.height * 0.07f)
    val pulse = 1f + 0.03f * sin(t * 0.7f)
    val warm = if (celsius >= 28) Color(0xFFFFB25A) else Color(0xFFFFE6B5)
    // Bloom: a wide warm wash, then a tight bright core.
    val r = size.width * 0.85f * pulse
    drawCircle(Brush.radialGradient(listOf(warm.copy(alpha = 0.30f * dim), warm.copy(alpha = 0.08f * dim), Color.Transparent), c, r), r, c)
    val core = size.width * 0.12f
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f * dim), warm.copy(alpha = 0.25f * dim), Color.Transparent), c, core), core, c)
    // Shafts of light, wide and faint, turning slowly.
    for (k in 0 until 5) {
        val a = (k * PI / 5 + t * 0.025).toFloat() + 1.75f
        val reach = size.height * (0.55f + 0.15f * hash(k, 66))
        val end = Offset(c.x + cos(a) * reach, c.y + sin(a) * reach)
        drawLine(
            Brush.linearGradient(listOf(warm.copy(alpha = 0.04f * dim), Color.Transparent), c, end),
            c, end, strokeWidth = size.width * (0.10f + 0.06f * hash(k, 67)), cap = StrokeCap.Round,
        )
    }
    // A lens's ghosts, along the line from the sun through the middle.
    val mid = Offset(size.width / 2, size.height / 2)
    listOf(0.55f to 0.05f, 0.9f to 0.025f, 1.35f to 0.07f).forEachIndexed { i, (along, rr) ->
        val p = Offset(c.x + (mid.x - c.x) * along, c.y + (mid.y - c.y) * along)
        val rad = size.width * rr
        drawCircle(
            Brush.radialGradient(listOf((if (i == 1) Color(0xFF9AD7FF) else warm).copy(alpha = 0.10f * dim), Color.Transparent), p, rad),
            rad, p,
        )
    }
    // Dust drifting in the light.
    for (i in 0 until 26) {
        val x = (hash(i, 71) * size.width + sin(t * 0.15f + i) * 30f * density / 2f)
        val y = ((hash(i, 72) * size.height * 0.7f - t * (4f + hash(i, 73) * 6f) * density / 2f) % (size.height * 0.7f) + size.height * 0.7f) % (size.height * 0.7f)
        val tw = 0.5f + 0.5f * sin(t * (1f + hash(i, 74) * 2f) + i)
        drawCircle(warm.copy(alpha = 0.25f * tw * dim), (0.8f + hash(i, 75) * 1.2f) * density / 2f, Offset(x, y))
    }
}

// ---------------------------------------------------------------- night --

private fun DrawScope.night(t: Float, stars: Int = 90) {
    val moon = Offset(size.width * 0.2f, size.height * 0.1f)
    val mr = size.width * 0.5f
    drawCircle(Brush.radialGradient(listOf(Color(0xFFDDE6FF).copy(alpha = 0.18f), Color(0xFFDDE6FF).copy(alpha = 0.05f), Color.Transparent), moon, mr), mr, moon)
    val disc = size.width * 0.04f
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFBF8EE), Color(0xFFE6E1D2)), moon, disc), disc, moon)
    // The moon's seas, faint.
    drawCircle(Color(0xFFCFC8B6).copy(alpha = 0.5f), disc * 0.28f, Offset(moon.x - disc * 0.3f, moon.y - disc * 0.15f))
    drawCircle(Color(0xFFCFC8B6).copy(alpha = 0.4f), disc * 0.2f, Offset(moon.x + disc * 0.25f, moon.y + disc * 0.3f))
    for (i in 0 until stars) {
        val x = hash(i, 1) * size.width
        val y = hash(i, 2) * size.height * 0.65f
        val tw = 0.55f + 0.45f * sin(t * (0.7f + hash(i, 3) * 2.4f) + hash(i, 4) * 6.28f)
        val r = (0.5f + hash(i, 5) * hash(i, 5) * 2.2f) * density / 2f
        val hue = when { hash(i, 8) > 0.85f -> Color(0xFFFFE2C2); hash(i, 8) < 0.15f -> Color(0xFFCADBFF); else -> Color.White }
        drawCircle(hue.copy(alpha = (0.25f + 0.65f * hash(i, 6)) * tw), r, Offset(x, y))
        if (r > 1.6f * density / 2f) drawCircle(hue.copy(alpha = 0.10f * tw), r * 3.5f, Offset(x, y))
    }
    // Now and then, a shooting star.
    val period = 23f
    val k = floor(t / period).toInt()
    val since = t - k * period - hash(k, 81) * 12f
    if (since in 0f..0.9f) {
        val p = since / 0.9f
        val from = Offset(size.width * (0.3f + hash(k, 82) * 0.6f), size.height * (0.05f + hash(k, 83) * 0.2f))
        val dir = Offset(-0.85f, 0.5f)
        val head = Offset(from.x + dir.x * size.width * 0.45f * p, from.y + dir.y * size.width * 0.45f * p)
        val tail = Offset(head.x - dir.x * size.width * 0.18f, head.y - dir.y * size.width * 0.18f)
        val fade = if (p < 0.8f) 1f else (1f - p) / 0.2f
        drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.85f * fade)), tail, head), tail, head, 1.6f * density / 2f, cap = StrokeCap.Round)
    }
}

// --------------------------------------------------------------- clouds --

/**
 * Clouds as clusters of puffs, each lit from above and shadowed below.
 * [grey] darkens them toward rain clouds; [lit] (0–1) is lightning inside.
 */
private fun DrawScope.clouds(t: Float, count: Int, amount: Float, day: Boolean, grey: Float = 0f, lit: Float = 0f) {
    val top = if (day) Color(0xFFFAFAF8) else Color(0xFF9AA3B5)
    val shade = if (day) Color(0xFFB9BEC6) else Color(0xFF4A5264)
    val dark = Color(0xFF3B424E)
    val under = lerpColor(shade, dark, grey)
    val litTop = lerpColor(lerpColor(top, shade, grey * 0.8f), Color(0xFFE6ECFF), lit)
    for (i in 0 until count) {
        val speed = 9f + hash(i, 11) * 14f
        val span = size.width * 2.2f
        val x = ((hash(i, 12) * span + t * speed * density / 2f) % span) - size.width * 0.6f
        val y = size.height * (0.02f + hash(i, 13) * 0.24f)
        val r = size.width * (0.2f + hash(i, 14) * 0.18f)
        val a = (0.07f + 0.05f * hash(i, 15)) * amount
        // Many wide, soft, overlapping puffs: a cloud, not a stack of discs.
        for (k in 0 until 12) {
            val px = x + (hash(i * 13 + k, 16) - 0.5f) * r * 2.6f
            val py = y + (hash(i * 13 + k, 17) - 0.5f) * r * 0.45f
            val pr = r * (0.35f + hash(i * 13 + k, 18) * 0.45f)
            withTransform({ scale(2.1f, 1f, Offset(px, py)) }) {
                val below = Offset(px, py + pr * 0.22f)
                drawCircle(Brush.radialGradient(listOf(under.copy(alpha = a), under.copy(alpha = a * 0.5f), Color.Transparent), below, pr), pr, below)
                val above = Offset(px, py - pr * 0.1f)
                val ta = (a * (0.9f - grey * 0.4f) + 0.3f * lit).coerceAtMost(0.7f)
                drawCircle(Brush.radialGradient(listOf(litTop.copy(alpha = ta), litTop.copy(alpha = ta * 0.4f), Color.Transparent), above, pr * 0.8f), pr * 0.8f, above)
            }
        }
    }
}

private fun lerpColor(a: Color, b: Color, f: Float): Color {
    val k = f.coerceIn(0f, 1f)
    return Color(a.red + (b.red - a.red) * k, a.green + (b.green - a.green) * k, a.blue + (b.blue - a.blue) * k, 1f)
}

// ------------------------------------------------------------------ fog --

private fun DrawScope.fog(t: Float) {
    val mist = Color(0xFFE9ECEF)
    // Veils with soft tops and bottoms, drifting at different heights…
    for (i in 0 until 4) {
        val y = size.height * (0.16f + i * 0.2f) + sin(t * 0.2f + i) * 18f
        val bandH = size.height * (0.22f + hash(i, 61) * 0.1f)
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, mist.copy(alpha = 0.11f), mist.copy(alpha = 0.15f), mist.copy(alpha = 0.11f), Color.Transparent),
                startY = y - bandH / 2,
                endY = y + bandH / 2,
            ),
            Offset(0f, y - bandH / 2),
            Size(size.width, bandH),
        )
    }
    // …and soft patches at two depths, the near ones faster, so it moves like fog.
    for (i in 0 until 10) {
        val near = i >= 6
        val r = size.width * (if (near) 0.5f + hash(i, 62) * 0.3f else 0.3f + hash(i, 62) * 0.25f)
        val span = size.width + 2 * r
        val x = ((hash(i, 63) * span + t * (if (near) 9f else 4f) * (1f + hash(i, 64)) * density / 2f) % span) - r
        val c = Offset(x, size.height * (0.1f + hash(i, 65) * 0.8f))
        drawCircle(Brush.radialGradient(listOf(mist.copy(alpha = if (near) 0.10f else 0.12f), Color.Transparent), c, r), r, c)
    }
}

// ----------------------------------------------------------------- rain --

/**
 * Rain at three depths. Far drops are short, thin and slow; near ones long,
 * thick and soft, as a camera sees them. Each drop is a bright head on a
 * fading tail, slanted by the wind.
 */
private fun DrawScope.rain(t: Float, count: Int, strength: Float, windKmh: Float, light: Boolean = false) {
    val slant = (windKmh / 40f).coerceIn(0f, 0.45f)
    val drop = Color(0xFFDCE7F2)
    val h = size.height
    val px = density / 2f
    for (i in 0 until count) {
        val depth = hash(i, 20) // 0 far … 1 near
        val layer = if (depth < 0.5f) 0 else if (depth < 0.85f) 1 else 2
        val speed = h * (if (light) 0.5f else 0.85f + strength * 0.45f) * (0.6f + 0.25f * layer + hash(i, 21) * 0.2f)
        val len = (if (light) 9f else 14f + 18f * strength) * px * (0.5f + 0.55f * layer + hash(i, 22) * 0.3f)
        val travel = h + len + slant * h
        val y = ((hash(i, 23) * travel + t * speed) % travel) - len
        val x = hash(i, 24) * (size.width + slant * h) - slant * h + y * slant
        val a = (if (light) 0.30f else 0.20f) * (0.55f + 0.45f * strength) * (0.45f + 0.35f * layer + 0.2f * hash(i, 25))
        val width = (0.8f + layer * (0.7f + strength * 0.5f)) * px
        val tail = Offset(x - slant * len, y)
        val mid = Offset(x - slant * len * 0.35f, y + len * 0.65f)
        val head = Offset(x, y + len)
        if (layer == 2) {
            // Near drops are out of focus: a soft wide halo under the streak.
            drawLine(drop.copy(alpha = a * 0.35f), tail, head, width * 3f, cap = StrokeCap.Round)
        }
        drawLine(drop.copy(alpha = a * 0.45f), tail, mid, width, cap = StrokeCap.Round)
        drawLine(drop.copy(alpha = a * 1.25f), mid, head, width, cap = StrokeCap.Round)
    }
}

/** Rings where heavy rain lands, near the bottom of the screen. */
private fun DrawScope.splashes(t: Float, count: Int) {
    for (i in 0 until count) {
        val period = 0.5f + hash(i, 31) * 0.7f
        val cycle = floor((t + hash(i, 32) * period) / period).toInt()
        val phase = ((t + hash(i, 32) * period) % period) / period
        val c = Offset(hash(i * 13 + cycle, 33) * size.width, size.height * (0.80f + hash(i * 13 + cycle, 34) * 0.18f))
        val r = (2f + 14f * phase) * density / 2f
        drawOval(
            Color.White.copy(alpha = 0.24f * (1f - phase)),
            topLeft = Offset(c.x - r * 1.7f, c.y - r * 0.45f),
            size = Size(r * 3.4f, r * 0.9f),
            style = Stroke(1f * density / 2f),
        )
        // The droplets thrown up, early in the splash.
        if (phase < 0.35f) {
            val up = (phase / 0.35f)
            for (d in -1..1 step 2) {
                drawCircle(Color.White.copy(alpha = 0.3f * (1f - up)), 1.1f * density / 2f, Offset(c.x + d * r * 1.2f, c.y - 8f * up * density / 2f * (1f - up) * 4f))
            }
        }
    }
}

/**
 * Water on the glass: drops that gather, sit, and every so often run down,
 * leaving a thin trail. Each is a lens — darker below, a highlight above.
 */
private fun DrawScope.glass(t: Float, count: Int, amount: Float) {
    val px = density / 2f
    for (i in 0 until count) {
        val period = 9f + hash(i, 91) * 14f
        val cycle = floor((t + hash(i, 92) * period) / period).toInt()
        val p = ((t + hash(i, 92) * period) % period) / period
        val r = (3.5f + hash(i * 17 + cycle, 93) * hash(i * 17 + cycle, 96) * 14f) * px * (0.6f + 0.4f * amount)
        val x0 = hash(i * 17 + cycle, 94) * size.width
        val y0 = hash(i * 17 + cycle, 95) * size.height * 0.85f
        val grow = (p / 0.15f).coerceAtMost(1f)
        // The last third: it runs, faster as it goes.
        val run = ((p - 0.66f) / 0.34f).coerceIn(0f, 1f)
        val y = y0 + run * run * size.height * 0.18f
        val x = x0 + sin(run * 9f + i) * 3f * px
        val rr = r * grow * (1f - 0.3f * run)
        if (rr < 0.5f) continue
        val fade = if (p > 0.95f) (1f - p) / 0.05f else 1f
        if (run > 0f) {
            // What a running drop leaves: a broken line of tiny beads.
            val steps = ((y - y0) / (rr * 1.4f)).toInt().coerceAtMost(14)
            for (b in 1..steps) {
                val by = y - rr - b * rr * 1.4f
                if (by < y0) break
                val br = rr * (0.18f + 0.22f * hash(i * 29 + b + cycle, 97))
                drawCircle(Color.White.copy(alpha = 0.16f * fade * (1f - b / 15f)), br, Offset(x0 + sin(b * 1.7f + i) * 2f * px, by))
            }
        }
        val c = Offset(x, y)
        drawCircle(Color(0xFF1A1F26).copy(alpha = 0.16f * fade), rr, Offset(c.x, c.y + rr * 0.15f))
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f * fade), Color.White.copy(alpha = 0.02f * fade)), c, rr), rr, c)
        drawCircle(Color.White.copy(alpha = 0.18f * fade), rr, c, style = Stroke(0.8f * px))
        drawCircle(Color.White.copy(alpha = 0.7f * fade), rr * 0.22f, Offset(c.x - rr * 0.35f, c.y - rr * 0.38f))
    }
}

// ----------------------------------------------------------------- snow --

/** Snow at three depths; the nearest flakes big, soft and out of focus. */
private fun DrawScope.snow(t: Float, count: Int, windKmh: Float) {
    val drift = (windKmh / 60f).coerceIn(0f, 0.5f)
    val h = size.height
    val px = density / 2f
    for (i in 0 until count) {
        val depth = hash(i, 40)
        val layer = if (depth < 0.55f) 0 else if (depth < 0.9f) 1 else 2
        val r = (1f + hash(i, 41) * 1.6f + layer * (1.4f + layer * 1.8f)) * px
        val speed = h * (0.035f + 0.03f * layer + hash(i, 42) * 0.03f)
        val y = ((hash(i, 43) * (h + 40f) + t * speed) % (h + 40f)) - 20f
        val sway = sin(t * (0.5f + hash(i, 44) * 0.6f) + hash(i, 45) * 6.28f) * (10f + 8f * layer) * px
        val x = ((hash(i, 46) * size.width + y * drift + sway) % size.width + size.width) % size.width
        val a = 0.45f + 0.15f * layer + 0.25f * hash(i, 47)
        if (layer == 2) {
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = a * 0.6f), Color.White.copy(alpha = 0f)), Offset(x, y), r * 1.6f), r * 1.6f, Offset(x, y))
        } else {
            drawCircle(Color.White.copy(alpha = a), r, Offset(x, y))
        }
    }
}

/** Snow that has settled along the bottom. */
private fun DrawScope.settled(amount: Float) {
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = amount)), startY = size.height * 0.86f, endY = size.height))
}

// ------------------------------------------------------------ lightning --

/** How bright the storm is at [t]: a flash every 6–14 s that flickers twice, 0 between. */
private fun flashOf(t: Float): Float {
    var start = 0f
    var k = 0
    while (true) {
        val gap = 6f + hash(k, 51) * 8f
        if (start + gap > t) break
        start += gap
        k++
    }
    val since = t - start
    if (k == 0 || since > 0.6f) return 0f
    return when {
        since < 0.08f -> 1f
        since < 0.16f -> 0.25f
        since < 0.26f -> 0.85f
        else -> (1f - (since - 0.26f) / 0.34f).coerceAtLeast(0f) * 0.6f
    }
}

/**
 * The flash lights the whole sky; two times in three a bolt comes down with
 * its branches, a different one each time; otherwise it stays inside the clouds.
 */
private fun DrawScope.lightning(t: Float, flash: Float) {
    if (flash <= 0f) return
    var start = 0f
    var k = 0
    while (true) {
        val gap = 6f + hash(k, 51) * 8f
        if (start + gap > t) break
        start += gap
        k++
    }
    val boltX = size.width * (0.15f + hash(k, 52) * 0.7f)
    // The light, strongest where it strikes.
    drawRect(Color(0xFFE8EEFF).copy(alpha = 0.22f * flash))
    val glowR = size.width * 0.9f
    drawCircle(Brush.radialGradient(listOf(Color(0xFFDDE6FF).copy(alpha = 0.35f * flash), Color.Transparent), Offset(boltX, size.height * 0.1f), glowR), glowR, Offset(boltX, size.height * 0.1f))
    if (hash(k, 58) < 0.33f) return

    fun bolt(fromX: Float, fromY: Float, toY: Float, seed: Int, width: Float, branches: Boolean) {
        val path = Path()
        var x = fromX
        var y = fromY
        path.moveTo(x, y)
        var s = 0
        val forks = mutableListOf<Offset>()
        while (y < toY) {
            x += (hash(seed * 31 + s, 54) - 0.5f) * size.width * 0.08f
            y += size.height * (0.018f + hash(seed * 31 + s, 55) * 0.03f)
            path.lineTo(x, y)
            if (branches && hash(seed * 31 + s, 56) > 0.78f) forks += Offset(x, y)
            s++
        }
        drawPath(path, Color(0xFFB9C9FF).copy(alpha = 0.25f * flash), style = Stroke(width * 6f, cap = StrokeCap.Round))
        drawPath(path, Color(0xFFDDE6FF).copy(alpha = 0.5f * flash), style = Stroke(width * 2.4f, cap = StrokeCap.Round))
        drawPath(path, Color.White.copy(alpha = 0.95f * flash), style = Stroke(width, cap = StrokeCap.Round))
        forks.forEachIndexed { b, f ->
            bolt(f.x, f.y, f.y + size.height * (0.06f + hash(seed * 7 + b, 59) * 0.1f), seed * 13 + b + 1, width * 0.55f, false)
        }
    }
    bolt(boltX, 0f, size.height * (0.4f + hash(k, 53) * 0.3f), k, 2.2f * density / 2f, true)
}

// --------------------------------------------------------------- feeling --

private fun DrawScope.frost(amount: Float) {
    val ice = Color(0xFFE3F1FF)
    val corners = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
    for (c in corners) {
        val r = size.width * (0.55f + 0.25f * amount)
        drawCircle(Brush.radialGradient(listOf(ice.copy(alpha = 0.22f * amount + 0.06f), Color.Transparent), c, r), r, c)
    }
    // Crystals growing in from the corners.
    for (i in 0 until (24 * amount).toInt()) {
        val corner = corners[i % 4]
        val a = hash(i, 101) * 1.57f
        val dist = size.width * (0.04f + hash(i, 102) * 0.18f) * amount
        val dx = if (corner.x == 0f) 1f else -1f
        val dy = if (corner.y == 0f) 1f else -1f
        val c = Offset(corner.x + dx * cos(a) * dist, corner.y + dy * sin(a) * dist)
        val len = (4f + hash(i, 103) * 8f) * density / 2f
        for (arm in 0 until 3) {
            val ang = arm * PI.toFloat() / 3f + hash(i, 104)
            drawLine(ice.copy(alpha = 0.35f), Offset(c.x - cos(ang) * len, c.y - sin(ang) * len), Offset(c.x + cos(ang) * len, c.y + sin(ang) * len), 0.8f * density / 2f)
        }
    }
}

private fun DrawScope.heat(t: Float, amount: Float) {
    val warm = Color(0xFFFF9A4A)
    val wave = 0.5f + 0.5f * sin(t * 0.5f)
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, warm.copy(alpha = (0.08f + 0.06f * wave) * amount)), startY = size.height * 0.55f, endY = size.height),
        blendMode = BlendMode.SrcOver,
    )
    // Shimmer: faint rising ribbons of warm air near the bottom.
    for (i in 0 until 5) {
        val x = size.width * (0.1f + i * 0.2f) + sin(t * 0.6f + i) * 12f
        val y = size.height * (0.98f - ((t * 0.05f + hash(i, 111)) % 1f) * 0.3f)
        drawLine(
            Brush.verticalGradient(listOf(Color.Transparent, warm.copy(alpha = 0.06f * amount), Color.Transparent), startY = y - 40f, endY = y + 40f),
            Offset(x, y - 40f), Offset(x + sin(t + i) * 10f, y + 40f), 18f * density / 2f, cap = StrokeCap.Round,
        )
    }
}

package com.aura.launcher.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import com.aura.launcher.data.AnimatedWallpaper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The animated backdrops.
 *
 * These fill the home surface when no photograph is set, and they run for as
 * long as the launcher is on screen — so what they cost matters as much as what
 * they look like. Four ideas carry the whole file.
 *
 * **Nothing is choreographed; everything is generated.** There is no list of
 * meteors, no list of clouds, no fixed place for a constellation. A meteor is a
 * number: which run of the meteor slot we are in, hashed into a path, a length,
 * a brightness, and a coin flip for whether that run happens at all. A cloud
 * that leaves the right edge comes back from the left as a different cloud.
 * The sky over an hour is an hour of arrangements that have not been on screen
 * before, out of code that stores none of them.
 *
 * **The scene never repeats.** Every animation asks [Clock] for the phase of its
 * own period, and the periods are chosen not to line up. There is no shared
 * cycle to come back around to, and so no loop to notice.
 *
 * **It has depth, and the phone is the window.** Tilt it and the layers slide
 * against each other: the stars barely, the moon a little, the cloud more, the
 * hills and the water most. That ordering is the entire illusion — parallax is
 * not movement, it is *different* movement at different distances.
 *
 * **It is drawn thirty times a second, and only while there is a screen to draw
 * on.** See [rememberSceneFrame]. Nothing here moves fast enough to want more,
 * and on a 120 Hz panel that is three frames in four never drawn.
 *
 * This composable paints the active home surface. Settings intentionally uses
 * lightweight still previews: starting a live scene for every choice would add
 * work and makes a frame-clock failure in a preview fatal to navigation.
 */
@Composable
fun AnimatedWallpaperBox(
    kind: AnimatedWallpaper,
    modifier: Modifier = Modifier,
) {
    if (kind == AnimatedWallpaper.NONE) {
        // The paper look is a plain fill; there is nothing to animate.
        Canvas(modifier.fillMaxSize().background(Aura.colors.paper)) { }
        return
    }

    val frame by rememberSceneFrame()

    Canvas(modifier.fillMaxSize()) {
        val clock = Clock(frame.timeMs / 1000.0)
        when (kind) {
            AnimatedWallpaper.NONE -> Unit
            AnimatedWallpaper.NIGHT_SKY -> drawNightSky(clock, frame)
            AnimatedWallpaper.CONSTELLATION -> drawConstellation(clock, frame)
        }
    }
}

private const val TAU = (2.0 * PI).toFloat()

/**
 * Whether to draw the fine detail.
 *
 * On a genuinely small render target, three hundred stars are three hundred
 * draws inside the same handful of pixels. The costly texture passes check this
 * first, which also keeps future thumbnails or tests inexpensive.
 */
private fun DrawScope.fineDetail(): Boolean = size.width >= 620f

/** A tiny deterministic generator, for the fields that are made once. */
private class Rng(private var state: Int) {
    fun next(): Float {
        state = state * 1664525 + 1013904223
        return ((state ushr 8) and 0xFFFFFF) / 16_777_216f
    }
}

private fun frac(v: Float): Float {
    val f = v % 1f
    return if (f < 0f) f + 1f else f
}

private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

/** Hermite ease between two thresholds — the shape every soft edge here uses. */
private fun smoothStep(from: Float, to: Float, v: Float): Float {
    if (to == from) return if (v < from) 0f else 1f
    val t = clamp01((v - from) / (to - from))
    return t * t * (3f - 2f * t)
}

/**
 * Runs [block] shifted for a layer standing [depth] away.
 *
 * Depth is a fraction of the frame's width: the stars get a thousandth of it and
 * the water eight hundredths. The sign is negative because tipping the phone to
 * the right is looking at the scene *from* the right, which slides everything
 * left — near things furthest, which is the only reason any of it reads as
 * distance rather than as the picture sliding about.
 *
 * Vertically it is halved. A phone is tipped up and down far more freely than it
 * is rolled, and matching that one for one made the horizon bob like a boat.
 */
private inline fun DrawScope.atDepth(frame: SceneFrame, depth: Float, block: DrawScope.() -> Unit) {
    if (!WALLPAPER_PARALLAX_ENABLED) {
        // Not a translate by zero: with the parallax off there is nothing to
        // offset, and a save/restore per layer per frame is work the scene
        // never asked for.
        block()
        return
    }
    val dx = -frame.tiltX * depth * size.width
    val dy = -frame.tiltY * depth * size.width * 0.5f
    withTransform({ translate(dx, dy) }) { block() }
}

// Depths, near to far. The ordering is the illusion; the numbers only set how
// pronounced it is, and small is right — this is a backdrop, not a diorama.
private const val DEPTH_STARS = 0.008f
private const val DEPTH_DUST = 0.014f
private const val DEPTH_MOON = 0.022f
private const val DEPTH_CLOUD = 0.040f
private const val DEPTH_RIDGE = 0.060f
private const val DEPTH_WATER = 0.085f

/** How far past the frame the near layers are drawn, so a shift never shows an edge. */
private const val OVERSCAN = 0.14f

// ------------------------------------------------------------- star field ---

/**
 * One star. Magnitude drives radius, alpha and whether it earns diffraction
 * spikes, exactly as brightness does through a real lens.
 *
 * [speed] is an integer so the twinkle survives the clock's wrap, and it varies
 * per star so the field scintillates instead of pulsing in unison — which is
 * the single thing that gives away a generated sky.
 */
private class Star(
    val x: Float,
    val y: Float,
    val mag: Float,
    val phase: Float,
    val speed: Int,
    val warm: Boolean,
)

/**
 * [scatter] stars spread over the frame plus [band] more crowded along a
 * diagonal — the Milky Way, which is what stops a starfield reading as
 * confetti. Magnitude is cubed on the way in so the great majority are faint
 * and only a handful are bright, which is how the real distribution looks.
 */
private fun starField(seed: Int, scatter: Int, band: Int): List<Star> = buildList {
    val r = Rng(seed)
    repeat(scatter) {
        val u = r.next()
        add(Star(r.next(), r.next(), 0.22f + u * u * u * 1.55f, r.next(), 1 + (r.next() * 3f).toInt(), r.next() < 0.24f))
    }
    repeat(band) {
        val along = r.next()
        // Sum of three uniforms: a cheap bell curve, so density falls off from
        // the axis rather than filling a hard-edged stripe.
        val across = (r.next() + r.next() + r.next() - 1.5f) * 0.42f
        val u = r.next()
        add(
            Star(
                x = -0.08f + 1.16f * along + across * 0.55f,
                y = 0.06f + 0.98f * along - across,
                mag = 0.14f + u * u * 0.70f,
                phase = r.next(),
                speed = 1 + (r.next() * 3f).toInt(),
                warm = r.next() < 0.30f,
            )
        )
    }
}

private val NIGHT_STARS = starField(seed = 20_260_828, scatter = 130, band = 190)
private val CHART_STARS = starField(seed = 4_411_907, scatter = 190, band = 150)

private val STAR_COOL = Color(0xFFDCE7FF)
private val STAR_WARM = Color(0xFFFFE3C4)

/** What the air does to a star's light on its way down to the horizon. */
private val STAR_EXTINCTED = Color(0xFFE8B98C)

/**
 * The field, drifting.
 *
 * Two things here are about the sky rather than about the picture. The drift is
 * diagonal and very slow — a whole frame width in twenty-five minutes — because
 * that is the direction and roughly the feel of stars setting. And the light is
 * *extincted* toward the horizon: near the ground it travels through many times
 * more air, so it arrives dimmer and redder. Skies painted without it look like
 * a flat sheet of dots hung behind a landscape, which is what this one did.
 */
private fun DrawScope.drawStars(
    stars: List<Star>,
    clock: Clock,
    dim: Float,
    spikes: Boolean,
    horizon: Float,
) {
    val w = size.width
    val h = size.height
    val unit = size.minDimension
    val fine = fineDetail()
    val stride = if (fine) 1 else 4

    val twinkleClock = clock.phase(4.3)
    val driftX = clock.phase(1_500.0)
    val driftY = clock.phase(2_300.0)

    var i = 0
    while (i < stars.size) {
        val s = stars[i]
        i += stride

        val x = frac(s.x + driftX)
        val y = frac(s.y + driftY)
        if (y > horizon) continue

        // The wrap has to be invisible: a star that pops into existence at the
        // top edge is the one thing that would say "this field is a loop".
        val edge = smoothStep(0f, 0.05f, y) * smoothStep(0f, 0.05f, horizon - y)

        val airmass = smoothStep(horizon * 0.52f, horizon * 0.99f, y)
        val extinction = 1f - 0.62f * airmass

        val twinkle = 0.68f + 0.32f * sin((twinkleClock * s.speed + s.phase) * TAU)
        val alpha = clamp01(s.mag * twinkle * dim * extinction * edge)
        if (alpha < 0.03f) continue

        val centre = Offset(w * x, h * y)
        val radius = unit * 0.0014f * (0.5f + s.mag * 1.3f)
        val colour = lerp(if (s.warm) STAR_WARM else STAR_COOL, STAR_EXTINCTED, airmass * 0.7f)

        // Only the brightest few bloom and spike. Giving every star a halo is
        // what turns a sky into a spray of identical dots.
        if (spikes && fine && s.mag > 1.32f) {
            val glow = radius * 7.5f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to colour.copy(alpha = alpha * 0.32f),
                    0.35f to colour.copy(alpha = alpha * 0.10f),
                    1f to Color.Transparent,
                    center = centre,
                    radius = glow,
                ),
                radius = glow,
                center = centre,
                blendMode = BlendMode.Plus,
            )
            val spike = radius * 6.2f
            drawLine(
                color = colour.copy(alpha = alpha * 0.38f),
                start = Offset(centre.x - spike, centre.y),
                end = Offset(centre.x + spike, centre.y),
                strokeWidth = radius * 0.5f,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus,
            )
            drawLine(
                color = colour.copy(alpha = alpha * 0.30f),
                start = Offset(centre.x, centre.y - spike * 0.78f),
                end = Offset(centre.x, centre.y + spike * 0.78f),
                strokeWidth = radius * 0.5f,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus,
            )
        }

        drawCircle(color = colour.copy(alpha = alpha), radius = radius, center = centre)
    }
}

/**
 * Interstellar dust: soft tilted lozenges that slide and breathe behind the
 * stars, drawn additively so they lift the field rather than sitting in front
 * of it as a grey smear. Each one runs on its own period, so the band's shape
 * is never quite the shape it was.
 */
private fun DrawScope.drawDust(
    spots: List<Triple<Float, Float, Float>>,
    clock: Clock,
    tint: Color,
    strength: Float,
) {
    if (!fineDetail()) return
    val w = size.width
    val h = size.height
    val unit = size.minDimension
    for ((i, spot) in spots.withIndex()) {
        val wander = clock.phase(430.0 + i * 97.0)
        val breathe = clock.phase(263.0 + i * 61.0)
        val centre = Offset(
            w * (spot.first + 0.05f * sin(wander * TAU)),
            h * (spot.second + 0.02f * cos(wander * TAU)),
        )
        val radius = unit * spot.third * (1f + 0.12f * sin(breathe * TAU))
        withTransform({
            rotate(28f + i * 7f, centre)
            scale(1.18f, 0.78f, centre)
        }) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to tint.copy(alpha = strength),
                    0.55f to tint.copy(alpha = strength * 0.42f),
                    1f to Color.Transparent,
                    center = centre,
                    radius = radius,
                ),
                radius = radius,
                center = centre,
                blendMode = BlendMode.Plus,
            )
        }
    }
}

/**
 * Meteors, made rather than listed.
 *
 * The sky is divided into slots of [slot] seconds. Most slots are empty — that
 * is what [chance] is for, and it is the whole point: a sky with a meteor
 * always crossing it is a screensaver, and a sky that is still and then, once
 * in a while, is not, is a sky. A slot that does fire hashes its own index into
 * a start, a direction, a length and a brightness, so no two are the same
 * streak and nothing about any of them is stored.
 */
private fun DrawScope.drawMeteors(clock: Clock, slot: Double, chance: Float, life: Double) {
    val w = size.width
    val h = size.height
    val unit = size.minDimension

    // The current slot and the one before it: a meteor that started near the
    // end of a slot has to be allowed to finish inside the next one.
    for (back in 0..1) {
        val episode = clock.episode(slot) - back
        if (!hashedChance(episode, 1, chance)) continue

        // Where in its slot this one falls, so they are not all on the beat.
        val offset = hashed(episode, 2, 0f, (slot - life).toFloat())
        val age = (clock.seconds - episode * slot - offset)
        if (age < 0.0 || age > life) continue

        val u = (age / life).toFloat()
        val alpha = sin(u * PI.toFloat()) * hashed(episode, 3, 0.55f, 1f)

        // Radiants cluster: real showers come from a point, and a sky where
        // every streak runs a different way looks like a screensaver again.
        val fromX = hashed(episode, 4, 0.15f, 1.05f)
        val fromY = hashed(episode, 5, -0.05f, 0.30f)
        val angle = hashed(episode, 6, 2.30f, 3.35f)
        val reach = hashed(episode, 7, 0.35f, 0.85f)

        val head = Offset(
            w * (fromX + cos(angle) * reach),
            h * (fromY + sin(angle) * reach * (w / h) * 1.6f),
        )
        val len = unit * hashed(episode, 8, 0.16f, 0.34f) * min(1f, u * 3f)
        val tail = Offset(head.x - cos(angle) * len, head.y - sin(angle) * len * 1.6f)

        drawLine(
            brush = Brush.linearGradient(
                0f to Color.Transparent,
                1f to Color(0xFFEAF1FF).copy(alpha = alpha),
                start = tail,
                end = head,
            ),
            start = tail,
            end = head,
            strokeWidth = unit * 0.0035f,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = alpha),
                1f to Color.Transparent,
                center = head,
                radius = unit * 0.020f,
            ),
            radius = unit * 0.020f,
            center = head,
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * A dark horizon, from three sine harmonics rather than random points so the
 * outline undulates like terrain instead of jittering like noise.
 *
 * Both night scenes get one, because a sky with no ground under it has no
 * scale: the same gradient is either a horizon twenty kilometres away or a wall
 * two metres away, and the ridge is what decides. It runs well past both edges
 * so a parallax shift can never bring the end of it into view.
 */
private fun DrawScope.ridgePath(baseY: Float, amplitude: Float, phase: Float): Path {
    val w = size.width
    val h = size.height
    val steps = 40
    val left = -w * OVERSCAN
    val right = w * (1f + OVERSCAN)
    val span = right - left
    return Path().apply {
        moveTo(left, h * (1f + OVERSCAN))
        lineTo(left, h * baseY)
        for (s in 0..steps) {
            val u = s / steps.toFloat()
            val y = h * baseY - h * amplitude * (
                0.60f * sin((u + phase) * TAU) +
                    0.28f * sin((u * 2f + phase * 1.7f) * TAU) +
                    0.14f * sin((u * 4f - phase * 0.6f) * TAU)
                )
            lineTo(left + span * u, y)
        }
        lineTo(right, h * (1f + OVERSCAN))
        close()
    }
}

// ------------------------------------------------------------- night sky ----

/** Where the shore is. Sky above, lake below. */
private const val WATER_Y = 0.845f

/** One flash on the water: how deep, how far off the centre line, and its phase. */
private class Glint(val depth: Float, val offset: Float, val phase: Float, val speed: Int, val len: Float)

private val GLITTER: List<Glint> = buildList {
    val r = Rng(6_060_842)
    repeat(170) { add(Glint(r.next(), r.next() * 2f - 1f, r.next(), 1 + (r.next() * 3f).toInt(), 0.4f + r.next())) }
}

private class Moon(val centre: Offset, val radius: Float, val phase: Float)

/**
 * The moon crosses on a slow arc — a quarter of an hour end to end, which is
 * about as slow as motion can be and still be motion.
 *
 * Its phase is on a period of its own, and a much longer one. Over five minutes
 * of looking it does not change; come back in the evening and it is a different
 * moon. That is the right speed for the one thing in the sky that has a shape
 * worth recognising.
 */
private fun DrawScope.moonAt(clock: Clock): Moon {
    val a = clock.phase(900.0) * TAU
    return Moon(
        centre = Offset(size.width * (0.50f + 0.30f * sin(a)), size.height * (0.235f - 0.085f * cos(a))),
        radius = size.minDimension * 0.085f,
        phase = 0.34f + 0.34f * sin(clock.phase(21_600.0) * TAU),
    )
}

/**
 * A wisp of high cloud, and a new one every time it crosses.
 *
 * The wisp travels from half a frame off the left to half a frame off the
 * right; when it wraps, its lap counter increments, and every number describing
 * it — how wide, how thick, how high, how bright — is hashed out of that
 * counter. So the wrap is not a loop, it is a different cloud arriving, and it
 * happens where nobody can see it because the wisp is off screen at both ends.
 */
private class CirrusLane(val base: Float, val period: Double, val salt: Int)

private val CIRRUS_LANES = listOf(
    CirrusLane(base = 0.10f, period = 620.0, salt = 11),
    CirrusLane(base = 0.55f, period = 830.0, salt = 23),
    CirrusLane(base = 0.83f, period = 1_040.0, salt = 37),
    CirrusLane(base = 0.31f, period = 1_270.0, salt = 51),
    CirrusLane(base = 0.68f, period = 1_490.0, salt = 67),
)

private class Wisp(
    val centre: Offset,
    val halfWidth: Float,
    val halfHeight: Float,
    val alpha: Float,
    val wobble: Float,
)

private fun DrawScope.wispAt(lane: CirrusLane, clock: Clock): Wisp {
    val travel = lane.base + (clock.seconds / lane.period).toFloat()
    val lap = floor(travel.toDouble()).toLong()
    val u = travel - lap
    val w = size.width
    val h = size.height
    return Wisp(
        centre = Offset(
            w * (u * 2f - 0.5f),
            h * hashed(lap, lane.salt, 0.08f, 0.72f),
        ),
        halfWidth = w * hashed(lap, lane.salt + 1, 0.22f, 0.44f),
        halfHeight = h * hashed(lap, lane.salt + 2, 0.011f, 0.028f),
        alpha = hashed(lap, lane.salt + 3, 0.13f, 0.36f),
        wobble = sin((clock.phase(97.0 + lane.salt) + hashed(lap, lane.salt + 4)) * TAU),
    )
}

/** A thin, ragged horizontal wisp — two uneven crowns over a sagging base. */
private fun wispPath(centre: Offset, halfW: Float, halfH: Float, wobble: Float): Path {
    val x0 = centre.x - halfW
    val x1 = centre.x + halfW
    val fw = halfW * 2f
    val cy = centre.y
    return Path().apply {
        moveTo(x0, cy)
        cubicTo(
            x0 + fw * 0.14f, cy - halfH * (1.0f + wobble * 0.3f),
            x0 + fw * 0.34f, cy - halfH * (1.5f + wobble * 0.4f),
            x0 + fw * 0.52f, cy - halfH * (0.9f + wobble * 0.2f),
        )
        cubicTo(
            x0 + fw * 0.68f, cy - halfH * (1.6f - wobble * 0.3f),
            x0 + fw * 0.88f, cy - halfH * (0.7f - wobble * 0.2f),
            x1, cy,
        )
        cubicTo(
            x1 - fw * 0.20f, cy + halfH * (0.5f + wobble * 0.1f),
            x0 + fw * 0.22f, cy + halfH * (0.4f - wobble * 0.1f),
            x0, cy,
        )
        close()
    }
}

private fun DrawScope.drawNightSky(clock: Clock, frame: SceneFrame) {
    val w = size.width
    val h = size.height
    val unit = size.minDimension
    val waterTop = h * WATER_Y

    // The sky itself does not move with the phone. It is the thing everything
    // else is measured against, and a backdrop that slides is a backdrop that
    // has a near edge.
    drawRect(
        brush = Brush.verticalGradient(
            0.00f to Color(0xFF050914),
            0.30f to Color(0xFF0A1226),
            0.62f to Color(0xFF152244),
            0.90f to Color(0xFF2B3D66),
            1.00f to Color(0xFF51648F),
            startY = 0f,
            endY = waterTop,
        ),
        size = Size(w, waterTop),
    )

    atDepth(frame, DEPTH_DUST) {
        drawDust(
            listOf(Triple(0.22f, 0.28f, 0.50f), Triple(0.52f, 0.52f, 0.56f), Triple(0.80f, 0.74f, 0.48f)),
            clock, Color(0xFF8FA6D8), 0.085f,
        )
    }
    atDepth(frame, DEPTH_STARS) {
        drawStars(NIGHT_STARS, clock, dim = 0.80f, spikes = true, horizon = WATER_Y)
    }

    val moon = moonAt(clock)

    // How much thin cloud is in front of the moon right now. One number, and
    // everything the moon lights dims with it: its own halo, the rim on the
    // hills, the pool on the lake.
    var veil = 0f
    for (lane in CIRRUS_LANES) {
        val wisp = wispAt(lane, clock)
        val dx = abs(wisp.centre.x - moon.centre.x) / (wisp.halfWidth + moon.radius)
        val dy = abs(wisp.centre.y - moon.centre.y) / (wisp.halfHeight * 4f + moon.radius)
        if (dx < 1f && dy < 1f) veil += (1f - dx) * (1f - dy) * wisp.alpha * 3f
    }
    val bright = 1f - veil.coerceIn(0f, 0.75f)

    atDepth(frame, DEPTH_MOON) {
        drawMoon(moon, bright)
        drawMeteors(clock, slot = 47.0, chance = 0.34f, life = 1.8)
    }

    // Cirrus in front of everything in the sky. The wisps near the moon are lit
    // from behind, which is the only reason thin cloud is ever visible at night
    // — and why they fade as the moon moves away from them.
    atDepth(frame, DEPTH_CLOUD) {
        for (lane in CIRRUS_LANES) {
            val wisp = wispAt(lane, clock)
            if (wisp.centre.x + wisp.halfWidth < -w * OVERSCAN) continue
            if (wisp.centre.x - wisp.halfWidth > w * (1f + OVERSCAN)) continue
            val near = clamp01(
                1f - hypot(wisp.centre.x - moon.centre.x, wisp.centre.y - moon.centre.y) / (unit * 0.85f)
            )
            drawPath(
                path = wispPath(wisp.centre, wisp.halfWidth, wisp.halfHeight, wisp.wobble),
                brush = Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.30f to Color(0xFF8296C4).copy(alpha = wisp.alpha * 0.30f * (1f + near)),
                    0.62f to Color(0xFF9FB2D8).copy(alpha = wisp.alpha * (0.42f + 0.85f * near * bright)),
                    0.88f to Color(0xFF6E7EA8).copy(alpha = wisp.alpha * 0.16f * (1f + near)),
                    1.00f to Color.Transparent,
                    startY = wisp.centre.y - wisp.halfHeight * 1.7f,
                    endY = wisp.centre.y + wisp.halfHeight * 0.6f,
                ),
                blendMode = BlendMode.Plus,
            )
        }
    }

    // Horizon wash, then the hills, then a rim of moonlight along their crest.
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color(0xFF4A5A87).copy(alpha = 0f),
            1f to Color(0xFF63749E).copy(alpha = 0.17f * bright + 0.05f),
            startY = h * 0.58f,
            endY = waterTop,
        ),
        topLeft = Offset(0f, h * 0.58f),
        size = Size(w, waterTop - h * 0.58f),
        blendMode = BlendMode.Plus,
    )

    atDepth(frame, DEPTH_RIDGE) {
        // The hills are drawn all the way down; the lake goes over them. A ridge
        // that stopped at the shoreline would open a gap under itself the moment
        // the two layers slid by different amounts.
        val ridge = ridgePath(baseY = 0.815f, amplitude = 0.038f, phase = 0.21f)
        drawPath(ridge, color = Color(0xFF060810))
        drawPath(
            path = ridge,
            brush = Brush.verticalGradient(
                0.00f to Color(0xFFA8BAE8).copy(alpha = 0.26f * bright),
                0.30f to Color.Transparent,
                startY = h * 0.775f,
                endY = h * 0.86f,
            ),
            blendMode = BlendMode.Plus,
        )
    }

    atDepth(frame, DEPTH_WATER) {
        drawWater(moon, bright, clock, frame)
    }
}

/**
 * A waxing crescent with its dark limb faintly visible, and marks on it.
 *
 * The crescent is a real shape — the disc minus an offset disc, cut with
 * [PathOperation.Difference] — rather than a bright circle with a
 * background-coloured circle bitten out of it. The bitten version only works
 * over a flat fill; over a gradient it leaves a visible disc of the wrong blue,
 * and over the Milky Way it erases the stars behind the moon.
 *
 * The shadowed part is then filled at very low alpha. That is earthshine, the
 * sunlight bouncing off the Earth onto the moon's night side. And the maria —
 * the dark seas — are what stop the lit part being a blank white sliver, which
 * is the shape of a logo rather than of the moon.
 */
private fun DrawScope.drawMoon(moon: Moon, bright: Float) {
    val centre = moon.centre
    val r = moon.radius

    drawCircle(
        brush = Brush.radialGradient(
            0f to Color(0xFFBFD2FF).copy(alpha = 0.17f * bright),
            0.28f to Color(0xFF8FA8E0).copy(alpha = 0.07f * bright),
            1f to Color.Transparent,
            center = centre,
            radius = r * 10f,
        ),
        radius = r * 10f,
        center = centre,
        blendMode = BlendMode.Plus,
    )
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color(0xFFFFF6E2).copy(alpha = 0.32f * bright),
            0.45f to Color(0xFFFFF6E2).copy(alpha = 0.10f * bright),
            1f to Color.Transparent,
            center = centre,
            radius = r * 2.8f,
        ),
        radius = r * 2.8f,
        center = centre,
        blendMode = BlendMode.Plus,
    )

    val disc = Path().apply { addOval(Rect(centre.x - r, centre.y - r, centre.x + r, centre.y + r)) }
    val sc = Offset(centre.x + r * moon.phase * 1.65f, centre.y - r * 0.30f)
    val sr = r * 1.03f
    val shadow = Path().apply { addOval(Rect(sc.x - sr, sc.y - sr, sc.x + sr, sc.y + sr)) }
    val crescent = Path().apply { op(disc, shadow, PathOperation.Difference) }
    val dark = Path().apply { op(disc, crescent, PathOperation.Difference) }

    drawPath(path = dark, color = Color(0xFF9FB0D8).copy(alpha = 0.06f))
    drawPath(
        path = crescent,
        brush = Brush.radialGradient(
            0f to Color(0xFFFFF8E8),
            0.72f to Color(0xFFFFF3DC),
            1f to Color(0xFFC9C2A8),
            // Brightest along the outer limb, which is where the sun is.
            center = Offset(centre.x - r * 0.5f, centre.y + r * 0.34f),
            radius = r * 1.95f,
        ),
    )

    if (fineDetail()) {
        clipPath(crescent) {
            for (m in MARIA) {
                val at = Offset(centre.x + r * m.first, centre.y + r * m.second)
                val rr = r * m.third
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color(0xFF8C8471).copy(alpha = 0.30f),
                        1f to Color.Transparent,
                        center = at,
                        radius = rr,
                    ),
                    radius = rr,
                    center = at,
                )
            }
        }
    }
}

/** Dark seas, placed by hand: they are a face people know. */
private val MARIA = listOf(
    Triple(-0.42f, -0.30f, 0.40f),
    Triple(-0.20f, 0.12f, 0.46f),
    Triple(-0.55f, 0.34f, 0.30f),
    Triple(0.02f, -0.52f, 0.26f),
)

/**
 * The lake.
 *
 * The pool of moonlight is built from overlapping soft ellipses that widen with
 * depth, not from a polygon: light on water has no edge, and a polygon gives it
 * two straight sides that stop the whole thing being water. On top of that, the
 * glitter — a hundred and seventy flashes scattered down the pool, each on its
 * own phase. It is the one thing in the scene that never stops moving, and it
 * is why the lake reads as a surface rather than as dark paint.
 */
private fun DrawScope.drawWater(moon: Moon, bright: Float, clock: Clock, frame: SceneFrame) {
    val w = size.width
    val h = size.height
    val unit = size.minDimension
    val top = h * WATER_Y
    val depth = h * (1f + OVERSCAN) - top

    drawRect(
        brush = Brush.verticalGradient(
            0.00f to Color(0xFF1B2947),
            0.35f to Color(0xFF101A30),
            1.00f to Color(0xFF080D18),
            startY = top,
            endY = h,
        ),
        topLeft = Offset(-w * OVERSCAN, top),
        size = Size(w * (1f + 2f * OVERSCAN), depth),
    )

    // The reflection answers to where the moon is, but a lake does not slide
    // under the phone the way its own surface does — so the pool is placed from
    // the moon's true position, undoing the parallax this layer is drawn under.
    val mx = (moon.centre.x - frame.tiltX * (DEPTH_MOON - DEPTH_WATER) * w)
        .coerceIn(w * 0.04f, w * 0.96f)

    clipRect(left = -w * OVERSCAN, top = top, right = w * (1f + OVERSCAN)) {
        for (j in 0..8) {
            val v = j / 8f
            val y = top + v * depth
            val radius = w * (0.07f + 0.34f * v)
            val alpha = bright * 0.11f * (1f - v * 0.55f)
            withTransform({ scale(1f, 0.34f, Offset(mx, y)) }) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color(0xFFA8BCEC).copy(alpha = alpha),
                        1f to Color.Transparent,
                        center = Offset(mx, y),
                        radius = radius,
                    ),
                    radius = radius,
                    center = Offset(mx, y),
                    blendMode = BlendMode.Plus,
                )
            }
        }

        val glitterClock = clock.phase(2.9)
        val stride = if (fineDetail()) 1 else 3
        var i = 0
        while (i < GLITTER.size) {
            val g = GLITTER[i]
            i += stride
            // The pool widens toward the viewer, so the flashes spread with it.
            val spread = 0.035f + 0.33f * g.depth
            val x = mx + g.offset * spread * w
            val y = top + g.depth * depth
            val twinkle = abs(sin((glitterClock * g.speed + g.phase) * TAU))
            val a = bright *
                clamp01(1f - abs(g.offset)).let { it * it * it } *
                (0.18f + 0.82f * twinkle) *
                (0.35f + 0.65f * g.depth)
            if (a < 0.03f) continue
            val len = unit * (0.006f + 0.026f * g.depth) * g.len
            drawLine(
                color = Color(0xFFE8F0FF).copy(alpha = a * 0.75f),
                start = Offset(x - len, y),
                end = Offset(x + len, y),
                strokeWidth = unit * (0.0018f + 0.0022f * g.depth),
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus,
            )
        }

        // A few ripple lines coming toward the viewer, on a long swell.
        if (fineDetail()) {
            val swell = clock.phase(61.0)
            val set = clock.phase(163.0)
            for (k in 0 until 7) {
                val v = frac(k / 7f + set)
                val y = top + v * depth
                val path = Path()
                for (s in 0..24) {
                    val u = s / 24f
                    val yy = y + sin((u * 3f + swell * 2f + k) * TAU) * unit * 0.004f * v
                    if (s == 0) path.moveTo(-w * OVERSCAN + w * (1f + 2f * OVERSCAN) * u, yy)
                    else path.lineTo(-w * OVERSCAN + w * (1f + 2f * OVERSCAN) * u, yy)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF9FB2D8).copy(alpha = 0.05f + 0.05f * v),
                    style = Stroke(width = unit * 0.0016f),
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }

    drawLine(
        color = Color(0xFFA8BAE8).copy(alpha = 0.16f * bright),
        start = Offset(-w * OVERSCAN, top),
        end = Offset(w * (1f + OVERSCAN), top),
        strokeWidth = unit * 0.0025f,
        blendMode = BlendMode.Plus,
    )
}

// ---------------------------------------------------------- constellation ---

/**
 * Figures anyone can name, at their real proportions.
 *
 * Scattering points and joining them at random looks like a network diagram.
 * These are Cassiopeia, Orion, the Plough and the Southern Cross — the last
 * because this launcher is used in the southern hemisphere, where it is the
 * shape actually overhead — and the difference is the whole scene: the eye
 * recognises Orion's belt before it has decided what it is looking at.
 *
 * Where a figure lands is not written down anywhere. Each slot hashes its
 * episode into a figure, a place and a size, so the same four constellations
 * arrive somewhere new every time and the sky is never twice the same chart.
 */
private class Figure(
    /** x, y, magnitude — in a local box the placement then maps into the frame. */
    val nodes: List<Triple<Float, Float, Float>>,
    val edges: List<Pair<Int, Int>>,
) {
    /** For each star, the edge that first reaches it — so it flares on cue. */
    val nodeEdge: IntArray = IntArray(nodes.size) { n ->
        edges.indexOfFirst { it.first == n || it.second == n }.coerceAtLeast(0)
    }

    /** How tall the figure is in its own box, for fitting it on the screen. */
    val extent: Float = nodes.maxOf { it.second }
}

private val CASSIOPEIA = Figure(
    nodes = listOf(
        Triple(0.00f, 0.22f, 1.05f),
        Triple(0.24f, 0.62f, 0.95f),
        Triple(0.50f, 0.16f, 1.25f),
        Triple(0.74f, 0.66f, 0.95f),
        Triple(1.00f, 0.10f, 1.05f),
    ),
    edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4),
)

private val ORION = Figure(
    nodes = listOf(
        Triple(0.08f, 0.10f, 1.60f), // Betelgeuse
        Triple(0.62f, 0.02f, 1.15f), // Bellatrix
        Triple(0.22f, 0.46f, 1.05f), // Alnitak
        Triple(0.36f, 0.50f, 1.20f), // Alnilam
        Triple(0.50f, 0.54f, 1.00f), // Mintaka
        Triple(0.16f, 0.92f, 1.05f), // Saiph
        Triple(0.70f, 0.88f, 1.55f), // Rigel
    ),
    edges = listOf(0 to 1, 0 to 2, 2 to 3, 3 to 4, 1 to 4, 4 to 6, 6 to 5, 5 to 2),
)

private val PLOUGH = Figure(
    nodes = listOf(
        Triple(0.00f, 0.30f, 1.25f), // Alkaid
        Triple(0.16f, 0.20f, 1.05f), // Mizar
        Triple(0.31f, 0.15f, 1.35f), // Alioth
        Triple(0.45f, 0.22f, 0.80f), // Megrez
        Triple(0.50f, 0.42f, 1.05f), // Phecda
        Triple(0.78f, 0.44f, 1.15f), // Merak
        Triple(0.80f, 0.16f, 1.45f), // Dubhe
    ),
    edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 3),
)

private val CRUX = Figure(
    nodes = listOf(
        Triple(0.52f, 0.00f, 1.35f), // Gacrux, the top of the cross
        Triple(0.44f, 1.00f, 1.60f), // Acrux, the foot and the brightest
        Triple(0.00f, 0.44f, 1.00f), // Delta Crucis
        Triple(0.92f, 0.52f, 1.30f), // Mimosa
        Triple(0.28f, 0.72f, 0.60f), // Epsilon, the one that spoils the symmetry
    ),
    edges = listOf(0 to 1, 2 to 3),
)

private val FIGURES = listOf(CASSIOPEIA, ORION, PLOUGH, CRUX)

/**
 * Which figure a trace draws.
 *
 * Not a free hash. The two slots run half a period apart, so the trace before
 * this one is still on screen on the other slot — and a free hash puts the same
 * constellation in both about a quarter of the time, which reads as a bug
 * rather than as a coincidence. Stepping by a number coprime with the catalogue
 * guarantees consecutive traces differ, and the rotation keeps the order from
 * being the same four every time round.
 */
private const val FIGURE_STEP = 3L

private fun figureFor(trace: Long): Figure {
    val n = FIGURES.size
    val rotation = (hashed(Math.floorDiv(trace, n.toLong()), 909) * n).toInt().coerceIn(0, n - 1)
    val index = Math.floorMod(trace * FIGURE_STEP + rotation, n.toLong()).toInt()
    return FIGURES[index]
}

/** A satellite: a steady dot crossing the whole frame, with no tail. */
private fun DrawScope.drawSatellites(clock: Clock) {
    val w = size.width
    val h = size.height
    val unit = size.minDimension
    val slot = 190.0
    val life = 26.0

    for (back in 0..1) {
        val episode = clock.episode(slot) - back
        if (!hashedChance(episode, 71, 0.55f)) continue
        val offset = hashed(episode, 72, 0f, (slot - life).toFloat())
        val age = clock.seconds - episode * slot - offset
        if (age < 0.0 || age > life) continue

        val u = (age / life).toFloat()
        val rightward = hashed(episode, 73) < 0.5f
        val fromY = hashed(episode, 74, 0.08f, 0.72f)
        val toY = fromY + hashed(episode, 75, -0.22f, 0.22f)
        val x = if (rightward) -0.06f + 1.12f * u else 1.06f - 1.12f * u
        val centre = Offset(w * x, h * (fromY + (toY - fromY) * u))
        val a = sin(u * PI.toFloat()) * 0.75f

        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFFFFE9C0).copy(alpha = a * 0.5f),
                1f to Color.Transparent,
                center = centre,
                radius = unit * 0.010f,
            ),
            radius = unit * 0.010f,
            center = centre,
            blendMode = BlendMode.Plus,
        )
        drawCircle(color = Color(0xFFFFF6E2).copy(alpha = a), radius = unit * 0.0022f, center = centre)
    }
}

/** Where a figure has been put this time round. */
private class Placement(val figure: Figure, val originX: Float, val originY: Float, val scale: Float)

/**
 * Picks a figure and a place for it, from the episode alone.
 *
 * [band] keeps the two slots out of each other's way — one draws in the upper
 * part of the frame and one in the lower — without either of them ever landing
 * in the same spot twice.
 */
private fun DrawScope.placeFigure(trace: Long, salt: Int, band: ClosedFloatingPointRange<Float>): Placement {
    val figure = figureFor(trace)
    val scale = hashed(trace, salt + 1, 0.42f, 0.62f)
    val tall = scale * figure.extent * size.width / size.height
    val room = (band.endInclusive - band.start - tall).coerceAtLeast(0f)
    return Placement(
        figure = figure,
        originX = hashed(trace, salt + 2, 0.07f, (0.93f - scale).coerceAtLeast(0.07f)),
        originY = band.start + hashed(trace, salt + 3) * room,
        scale = scale,
    )
}

private fun DrawScope.figurePoint(p: Placement, index: Int): Offset {
    val n = p.figure.nodes[index]
    return Offset(
        size.width * p.originX + n.first * p.scale * size.width,
        size.height * p.originY + n.second * p.scale * size.width,
    )
}

private fun DrawScope.drawConstellation(clock: Clock, frame: SceneFrame) {
    val w = size.width
    val unit = size.minDimension

    drawRect(
        brush = Brush.verticalGradient(
            0.00f to Color(0xFF04050C),
            0.42f to Color(0xFF0A0D1E),
            0.80f to Color(0xFF121734),
            1.00f to Color(0xFF1B2246),
        )
    )

    atDepth(frame, DEPTH_DUST) {
        drawDust(
            listOf(Triple(0.30f, 0.22f, 0.46f), Triple(0.70f, 0.48f, 0.52f), Triple(0.35f, 0.80f, 0.44f)),
            clock, Color(0xFF6E7ACD), 0.075f,
        )
        drawDust(
            listOf(Triple(0.62f, 0.30f, 0.30f), Triple(0.25f, 0.62f, 0.26f)),
            clock, Color(0xFFB0688F), 0.055f,
        )
    }
    atDepth(frame, DEPTH_STARS) {
        drawStars(CHART_STARS, clock, dim = 0.62f, spikes = false, horizon = 1.02f)
    }

    // Two slots, half a period apart, so one figure is always being traced
    // while the one before it is letting go. The upper slot works the top of
    // the frame and the lower slot the bottom, which is what keeps two
    // randomly placed figures from landing on top of each other.
    val period = 96.0
    atDepth(frame, DEPTH_MOON) {
        // Two slots, half a period apart, so one figure is always being traced
        // while the one before it is letting go. `parity` numbers them into one
        // sequence in time order, which is what lets consecutive traces be told
        // to pick different constellations.
        drawFigureSlot(clock, offset = 0.0, period = period, parity = 0, salt = 101, band = 0.06f..0.52f)
        drawFigureSlot(clock, offset = period / 2, period = period, parity = -1, salt = 211, band = 0.46f..0.94f)
        drawSatellites(clock)
        drawMeteors(clock, slot = 62.0, chance = 0.40f, life = 1.6)
    }
}

private fun DrawScope.drawFigureSlot(
    clock: Clock,
    offset: Double,
    period: Double,
    parity: Int,
    salt: Int,
    band: ClosedFloatingPointRange<Float>,
) {
    val shifted = Clock(clock.seconds + offset)
    val episode = shifted.episode(period)
    val trace = episode * 2 + parity
    val p = shifted.within(period)

    val draw = clamp01(p / 0.30f)
    val alive = 1f - clamp01((p - 0.78f) / 0.22f)
    if (alive <= 0.01f) return

    val unit = size.minDimension
    val place = placeFigure(trace, salt, band)
    val f = place.figure

    // The figure lights the sky it stands in while it is up.
    var gx = 0f
    var gy = 0f
    for (i in f.nodes.indices) {
        val pt = figurePoint(place, i)
        gx += pt.x
        gy += pt.y
    }
    val centroid = Offset(gx / f.nodes.size, gy / f.nodes.size)
    val glow = place.scale * size.width * 0.9f
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color(0xFF6E86D8).copy(alpha = 0.13f * alive * draw),
            1f to Color.Transparent,
            center = centroid,
            radius = glow,
        ),
        radius = glow,
        center = centroid,
        blendMode = BlendMode.Plus,
    )

    val n = f.edges.size
    for ((i, edge) in f.edges.withIndex()) {
        val from = i / n.toFloat()
        val span = 1f / n
        val k = clamp01((draw - from) / span)
        if (k <= 0f) continue
        val a = figurePoint(place, edge.first)
        val b = figurePoint(place, edge.second)
        val end = Offset(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)
        // The line is brightest right where it is being drawn, and settles once
        // the head has moved on.
        val hot = if (k < 1f) 1f else clamp01(1f - (draw - (from + span)) * 4f)

        drawLine(
            color = Color(0xFF7C93D8).copy(alpha = (0.10f + 0.22f * hot) * alive),
            start = a, end = end,
            strokeWidth = unit * 0.011f,
            cap = StrokeCap.Round,
            blendMode = BlendMode.Plus,
        )
        drawLine(
            color = Color(0xFFD6E4FF).copy(alpha = (0.30f + 0.55f * hot) * alive),
            start = a, end = end,
            strokeWidth = unit * 0.0022f,
            cap = StrokeCap.Round,
        )
        if (k < 1f) {
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.9f * alive),
                    1f to Color.Transparent,
                    center = end,
                    radius = unit * 0.006f,
                ),
                radius = unit * 0.006f,
                center = end,
                blendMode = BlendMode.Plus,
            )
        }
    }

    for ((i, node) in f.nodes.withIndex()) {
        val at = f.nodeEdge[i] / n.toFloat()
        val on = clamp01((draw - at) * 6f)
        if (on <= 0f) continue
        val flare = clamp01(1f - (draw - at) * 5f)
        val centre = figurePoint(place, i)
        val r = unit * 0.0055f * node.third * (1f + 0.5f * flare) * alive

        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFFDCE9FF).copy(alpha = (0.30f + 0.45f * flare) * on * alive),
                1f to Color.Transparent,
                center = centre,
                radius = r * 7f,
            ),
            radius = r * 7f,
            center = centre,
            blendMode = BlendMode.Plus,
        )
        if (node.third > 1.2f) {
            val spike = r * 6f * (1f + 0.3f * flare)
            drawLine(
                color = Color(0xFFEAF2FF).copy(alpha = (0.32f + 0.35f * flare) * on * alive),
                start = Offset(centre.x - spike, centre.y),
                end = Offset(centre.x + spike, centre.y),
                strokeWidth = r * 0.42f,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus,
            )
            drawLine(
                color = Color(0xFFEAF2FF).copy(alpha = (0.26f + 0.30f * flare) * on * alive),
                start = Offset(centre.x, centre.y - spike * 0.8f),
                end = Offset(centre.x, centre.y + spike * 0.8f),
                strokeWidth = r * 0.42f,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus,
            )
        }
        drawCircle(color = Color(0xFFF8FAFF).copy(alpha = 0.92f * on * alive), radius = r, center = centre)
    }
}

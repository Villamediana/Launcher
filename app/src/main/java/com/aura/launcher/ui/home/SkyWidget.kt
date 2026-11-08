package com.aura.launcher.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aura.launcher.R
import com.aura.launcher.data.Locator
import com.aura.launcher.ui.theme.Aura
import com.aura.launcher.ui.theme.AuraColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The day's sky as one drawing: the sun's arc from where it rose to where it
 * will set, the part it has already travelled filled with a little light, and
 * the sun itself glowing where it is now. At night the arc goes quiet and the
 * moon takes its place, drawn in its real phase.
 *
 * Thin lines and glow on nothing — no card, straight onto the wallpaper, like
 * the clock. Under it: sunrise on the left, sunset on the right, and in the
 * middle the one thing worth saying about the light right now.
 *
 * Everything is computed on the phone ([SkyMath]); it needs only a rough idea
 * of where you are, which the weather already has.
 */
@Composable
fun SkyCard() {
    val context = LocalContext.current
    val colors = Aura.colors
    var latLon by remember { mutableStateOf(Locator.lastKnown(context)?.let { it.latitude to it.longitude }) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var arrivals by remember { mutableIntStateOf(0) }

    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) arrivals++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(arrivals) {
        Locator.currentOrLastKnown(context)?.let { latLon = it.latitude to it.longitude }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(60_000L - now % 60_000L + 50)
            }
        }
    }

    val (lat, lon) = latLon ?: return
    val today = remember(lat, lon, now / 3_600_000L) { SkyMath.sunDay(lat, lon, localNoon(now, 0)) }
    val tomorrow = remember(lat, lon, now / 3_600_000L) { SkyMath.sunDay(lat, lon, localNoon(now, 1)) }
    val progress = SkyMath.dayProgress(today, now)
    val isDay = progress != null && progress in 0f..1f
    val age = remember(now / 3_600_000L) { SkyMath.moonAge(now) }

    // The sun rises into place every time the home screen comes back: it
    // travels its arc from the horizon to where it really is.
    val travel = remember { Animatable(0f) }
    LaunchedEffect(arrivals, isDay) {
        travel.snapTo(0f)
        travel.animateTo(1f, tween(1400, easing = FastOutSlowInEasing))
    }

    val hm = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val caption = when {
        isDay && today.set != null -> stringResource(R.string.sky_sunset_in, span(today.set - now))
        today.rise != null && now < today.rise -> stringResource(R.string.sky_sunrise_at, hm.format(Date(today.rise)))
        tomorrow.rise != null -> stringResource(R.string.sky_sunrise_at, hm.format(Date(tomorrow.rise)))
        else -> ""
    }
    val moonName = stringResource(moonPhaseName(age))

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(SKY_HEIGHT)) {
            drawSky(
                colors = colors,
                dayProgress = if (isDay) progress!! * travel.value else null,
                nightProgress = if (!isDay) nightProgress(today, tomorrow, now)?.times(travel.value) else null,
                moonAge = age,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(
                text = today.rise?.let { hm.format(Date(it)) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkFaint,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (isDay) caption else "$caption · $moonName",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = today.set?.let { hm.format(Date(it)) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkFaint,
            )
        }
    }
}

private fun DrawScope.drawSky(
    colors: AuraColors,
    dayProgress: Float?,
    nightProgress: Float?,
    moonAge: Double,
) {
    val w = size.width
    val h = size.height
    val horizon = h * 0.86f
    val peak = h * 0.10f
    val left = w * 0.04f
    val right = w * 0.96f
    fun at(p: Float) = Offset(
        left + (right - left) * p,
        horizon - (horizon - peak) * sin(PI * p).toFloat(),
    )
    fun arc(to: Float) = Path().apply {
        val steps = 64
        moveTo(left, horizon)
        for (i in 1..steps) {
            val o = at(to * i / steps)
            lineTo(o.x, o.y)
        }
    }

    // Horizon: a hairline that fades out at both ends.
    drawLine(
        brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.15f to colors.ink.copy(alpha = 0.28f),
            0.85f to colors.ink.copy(alpha = 0.28f),
            1f to Color.Transparent,
        ),
        start = Offset(0f, horizon),
        end = Offset(w, horizon),
        strokeWidth = 1.dp.toPx(),
    )

    // The whole day's path, dotted: where the sun goes.
    drawPath(
        path = arc(1f),
        color = colors.ink.copy(alpha = if (dayProgress != null) 0.22f else 0.12f),
        style = Stroke(
            width = 1.2.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 5.dp.toPx())),
        ),
    )

    if (dayProgress != null) {
        val p = dayProgress.coerceIn(0f, 1f)
        val sun = at(p)
        // The light already spent: a wash under the travelled arc.
        val filled = arc(p).apply {
            lineTo(sun.x, horizon)
            close()
        }
        drawPath(
            path = filled,
            brush = Brush.verticalGradient(
                0f to colors.accent.copy(alpha = 0.16f),
                1f to Color.Transparent,
                startY = peak,
                endY = horizon,
            ),
        )
        drawPath(
            path = arc(p),
            brush = Brush.horizontalGradient(
                0f to colors.accent.copy(alpha = 0.1f),
                1f to colors.accent.copy(alpha = 0.75f),
                startX = left,
                endX = sun.x.coerceAtLeast(left + 1f),
            ),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
        )
        // The sun: a wide soft glow round a small bright core.
        val glow = 26.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                0f to colors.accent.copy(alpha = 0.45f),
                0.35f to colors.accent.copy(alpha = 0.16f),
                1f to Color.Transparent,
                center = sun,
                radius = glow,
            ),
            radius = glow,
            center = sun,
        )
        drawCircle(colors.accent, radius = 5.5.dp.toPx(), center = sun)
    } else {
        // Night: the moon, riding the same arc from sunset to sunrise.
        val moon = at((nightProgress ?: 0.5f).coerceIn(0.08f, 0.92f))
        val r = 9.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                0f to colors.ink.copy(alpha = 0.18f),
                1f to Color.Transparent,
                center = moon,
                radius = r * 3.2f,
            ),
            radius = r * 3.2f,
            center = moon,
        )
        // The unlit disc, only just there, so the shape of the whole moon reads.
        drawCircle(colors.ink.copy(alpha = 0.12f), radius = r, center = moon)
        drawPath(moonLitPath(moon, r, moonAge), colors.ink.copy(alpha = 0.9f))
    }
}

/**
 * The lit part of the moon's disc for [age] (0 new → 0.5 full → 1 new).
 * One half-circle on the lit side, closed by the terminator: a half-ellipse
 * whose width is the cosine of the phase, bulging out for a crescent and in
 * for a gibbous moon.
 */
private fun moonLitPath(c: Offset, r: Float, age: Double): Path {
    val waxing = age < 0.5
    val k = cos(2 * PI * age).toFloat() // 1 new, -1 full
    val ex = abs(k) * r
    return Path().apply {
        // Lit edge: right half while waxing, left half while waning.
        arcTo(Rect(c.x - r, c.y - r, c.x + r, c.y + r), -90f, if (waxing) 180f else -180f, true)
        // Terminator back to the top. For a crescent (k > 0) it curves toward
        // the lit side; for a gibbous moon (k < 0), away from it.
        val towardLit = k > 0
        val sweep = if (waxing == towardLit) -180f else 180f
        arcTo(Rect(c.x - ex, c.y - r, c.x + ex, c.y + r), 90f, sweep, false)
        close()
    }
}

private fun nightProgress(today: SkyMath.SunDay, tomorrow: SkyMath.SunDay, now: Long): Float? {
    val start = if (today.set != null && now >= today.set) today.set else null
    return when {
        start != null && tomorrow.rise != null ->
            ((now - start).toFloat() / (tomorrow.rise - start)).coerceIn(0f, 1f)
        today.rise != null && now < today.rise -> {
            // Before dawn: from about the previous sunset (a day earlier) to rise.
            val prevSet = today.set?.minus(86_400_000L) ?: return null
            ((now - prevSet).toFloat() / (today.rise - prevSet)).coerceIn(0f, 1f)
        }
        else -> null
    }
}

private fun localNoon(now: Long, plusDays: Int): Long = Calendar.getInstance().apply {
    timeInMillis = now
    add(Calendar.DAY_OF_YEAR, plusDays)
    set(Calendar.HOUR_OF_DAY, 12)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** "2h 10min", "35 min". */
private fun span(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}min"
}

private fun moonPhaseName(age: Double): Int = when {
    age < 0.03 || age >= 0.97 -> R.string.moon_new
    age < 0.22 -> R.string.moon_waxing_crescent
    age < 0.28 -> R.string.moon_first_quarter
    age < 0.47 -> R.string.moon_waxing_gibbous
    age < 0.53 -> R.string.moon_full
    age < 0.72 -> R.string.moon_waning_gibbous
    age < 0.78 -> R.string.moon_last_quarter
    else -> R.string.moon_waning_crescent
}

private val SKY_HEIGHT = 96.dp
